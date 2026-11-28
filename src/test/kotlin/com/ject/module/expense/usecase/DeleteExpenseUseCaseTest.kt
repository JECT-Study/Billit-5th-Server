package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.global.error.ErrorCode
import com.ject.module.expense.domain.ExpenseEditLockResult
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeleteExpenseUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var deleteExpenseUseCase: DeleteExpenseUseCase

    @Autowired
    private lateinit var acquireExpenseEditLockUseCase: AcquireExpenseEditLockUseCase

    @MockitoSpyBean
    private lateinit var expenseRepository: ExpenseRepository

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM expense_share_amount WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM expense_item_member WHERE expense_id IN (SELECT id FROM expense WHERE trip_id = ?)", id)
                jdbc.update("DELETE FROM expense_item WHERE expense_id IN (SELECT id FROM expense WHERE trip_id = ?)", id)
                jdbc.update("DELETE FROM expense WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM settlement WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("기록자가 지출을 삭제하면 삭제 시각과 수정 시각을 같은 시각으로 기록하고 품목, 부담액과 생성 고유키는 그대로 둔다") {
            // given
            val trip = createTrip()
            val expenseId = insertExpense(trip, splitType = "ITEM")
            // 피자 8,000원을 기록자와 결제자가, 음료 4,000원을 기록자가 부담하는 품목과 부담액입니다.
            insertItem(expenseId, "피자", 8000, sortOrder = 0, tripMemberIds = listOf(trip.recorderId, trip.payerId))
            insertItem(expenseId, "음료", 4000, sortOrder = 1, tripMemberIds = listOf(trip.recorderId))
            insertShareAmount(trip.tripId, expenseId, trip.recorderId, 8000)
            insertShareAmount(trip.tripId, expenseId, trip.payerId, 4000)
            val expenseBefore = findExpenseRow(expenseId)
            val dependentsBefore = findDependentRows(expenseId)
            val before = Instant.now().truncatedTo(ChronoUnit.MILLIS)

            // when
            deleteExpenseUseCase.delete(trip.recorderMemberId, trip.tripId, expenseId)

            // then
            val after = Instant.now()
            val expenseAfter = findExpenseRow(expenseId)
            val deletedAt = (expenseAfter["deleted_at"] as Timestamp?).shouldNotBeNull().toInstant()
            assertSoftly {
                expenseBefore["deleted_at"] shouldBe null
                deletedAt shouldBeGreaterThanOrEqualTo before
                deletedAt shouldBeLessThanOrEqualTo after
                expenseAfter["updated_at"] shouldBe expenseAfter["deleted_at"]
                // 삭제 시각과 수정 시각 말고는 생성 고유키와 생성 시각을 포함해 지출 행의 어떤 칼럼도 바뀌지 않습니다.
                expenseAfter - "deleted_at" - "updated_at" shouldBe expenseBefore - "deleted_at" - "updated_at"
                findDependentRows(expenseId) shouldBe dependentsBefore
            }
        }

        test("기록자가 아니어도 여행 관리자는 삭제할 수 있고, 결제자이거나 나간 회원이면 403으로 거절하고 지출을 남긴다") {
            // given
            val trip = createTrip()
            val adminTarget = insertExpense(trip)
            val rejectedTarget = insertExpense(trip)

            // when
            deleteExpenseUseCase.delete(trip.adminMemberId, trip.tripId, adminTarget)
            val failures =
                mapOf("결제자" to trip.payerMemberId, "나간 회원" to trip.leftMemberId).mapValues { (_, memberId) ->
                    shouldThrow<BusinessException> { deleteExpenseUseCase.delete(memberId, trip.tripId, rejectedTarget) }.errorCode
                }

            // then
            assertSoftly {
                (findExpenseRow(adminTarget)["deleted_at"] != null) shouldBe true
                failures shouldBe mapOf<String, ErrorCode>("결제자" to CommonErrorCode.FORBIDDEN, "나간 회원" to CommonErrorCode.FORBIDDEN)
                findExpenseRow(rejectedTarget)["deleted_at"] shouldBe null
            }
        }

        test("여행이나 지출이 없거나 다른 여행의 지출이면 404로 거절한다") {
            // given
            val trip = createTrip()
            val otherTrip = createTrip()
            val expenseId = insertExpense(trip)
            val otherTripExpenseId = insertExpense(otherTrip)

            // when
            val failures =
                mapOf(
                    "없는 여행" to Pair(Long.MAX_VALUE, expenseId),
                    "없는 지출" to Pair(trip.tripId, Long.MAX_VALUE),
                    "다른 여행의 지출" to Pair(trip.tripId, otherTripExpenseId),
                ).mapValues { (_, request) ->
                    shouldThrow<BusinessException> {
                        deleteExpenseUseCase.delete(trip.adminMemberId, request.first, request.second)
                    }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe
                    mapOf<String, ErrorCode>(
                        "없는 여행" to CommonErrorCode.NOT_FOUND,
                        "없는 지출" to CommonErrorCode.NOT_FOUND,
                        "다른 여행의 지출" to CommonErrorCode.NOT_FOUND,
                    )
                findExpenseRow(expenseId)["deleted_at"] shouldBe null
                findExpenseRow(otherTripExpenseId)["deleted_at"] shouldBe null
            }
        }

        test("이미 삭제한 지출을 다시 삭제하면 404로 거절하고 처음 삭제 시각을 그대로 둔다") {
            // given
            val trip = createTrip()
            val expenseId = insertExpense(trip)
            deleteExpenseUseCase.delete(trip.recorderMemberId, trip.tripId, expenseId)
            val afterFirstDelete = findExpenseRow(expenseId)

            // when
            val failure = shouldThrow<BusinessException> { deleteExpenseUseCase.delete(trip.recorderMemberId, trip.tripId, expenseId) }

            // then
            assertSoftly {
                (afterFirstDelete["deleted_at"] != null) shouldBe true
                failure.errorCode shouldBe CommonErrorCode.NOT_FOUND
                findExpenseRow(expenseId) shouldBe afterFirstDelete
            }
        }

        test("만료되지 않은 수정 잠금이 있으면 다른 회원의 잠금이든 본인의 잠금이든 409 EXPENSE_EDIT_LOCKED로 거절하고 지출과 잠금을 그대로 둔다") {
            // given
            val trip = createTrip()
            val expenseId = insertExpense(trip, lock = Lock(trip.recorderId, UUID.randomUUID(), Instant.now().plusSeconds(60)))
            val before = findExpenseRow(expenseId)

            // when
            val failures =
                mapOf("다른 회원의 잠금" to trip.adminMemberId, "본인의 잠금" to trip.recorderMemberId).mapValues { (_, memberId) ->
                    shouldThrow<BusinessException> { deleteExpenseUseCase.delete(memberId, trip.tripId, expenseId) }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe
                    mapOf<String, ErrorCode>(
                        "다른 회원의 잠금" to ExpenseErrorCode.EDIT_LOCKED,
                        "본인의 잠금" to ExpenseErrorCode.EDIT_LOCKED,
                    )
                findExpenseRow(expenseId) shouldBe before
            }
        }

        test("수정 잠금이 만료된 지출은 삭제할 수 있다") {
            // given
            val trip = createTrip()
            val expenseId = insertExpense(trip, lock = Lock(trip.adminId, UUID.randomUUID(), Instant.now().minusSeconds(1)))

            // when
            deleteExpenseUseCase.delete(trip.recorderMemberId, trip.tripId, expenseId)

            // then
            (findExpenseRow(expenseId)["deleted_at"] != null) shouldBe true
        }

        test("정산이 점유한 지출이면 409 EXPENSE_SETTLEMENT_LOCKED로 거절하고 지출을 남긴다") {
            // given
            val trip = createTrip()
            val settlementId = insertSettlement(trip.tripId)
            // 정산 점유는 수정 잠금을 비우지만, 잠금 칼럼이 남은 경우에도 정산 점유를 먼저 알리는지 함께 확인합니다.
            val expenseIds =
                mapOf(
                    "잠금이 비워진 지출" to insertExpense(trip, settlementId = settlementId),
                    "잠금이 남은 지출" to
                        insertExpense(
                            trip,
                            lock = Lock(trip.recorderId, UUID.randomUUID(), Instant.now().plusSeconds(60)),
                            settlementId = settlementId,
                        ),
                )
            val before = expenseIds.mapValues { findExpenseRow(it.value) }

            // when
            val failures =
                expenseIds.mapValues { (_, expenseId) ->
                    shouldThrow<BusinessException> { deleteExpenseUseCase.delete(trip.recorderMemberId, trip.tripId, expenseId) }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe expenseIds.mapValues { ExpenseErrorCode.SETTLEMENT_LOCKED }
                expenseIds.mapValues { findExpenseRow(it.value) } shouldBe before
            }
        }

        test("같은 지출의 삭제와 수정 잠금 획득이 동시에 오면 하나만 반영하고, 삭제가 반영되면 잠금 획득은 404를, 잠금이 걸리면 삭제는 409 EXPENSE_EDIT_LOCKED를 받는다") {
            // given
            val trip = createTrip()
            // 어느 요청이 먼저 반영될지는 실행마다 달라 지출 30건으로 되풀이하며, 두 순서가 모두 나온다고 보장하지는 않습니다.
            val expenseIds = (1..30).map { insertExpense(trip) }
            // 두 요청이 모두 권한 확인과 새 잠금 준비를 마친 뒤에 UPDATE를 함께 실행하도록 맞춥니다. 장벽은 회차마다 다시 쓰입니다.
            val barrier = CyclicBarrier(2)
            val awaitBarrier =
                Answer<Any?> { invocation ->
                    barrier.await(10, TimeUnit.SECONDS)
                    invocation.callRealMethod()
                }
            doAnswer(awaitBarrier).`when`(expenseRepository).deleteExpenseIfUnlocked(anyLong(), anyLong(), anyInstant())
            doAnswer(awaitBarrier)
                .`when`(expenseRepository)
                .acquireEditLock(anyLong(), anyLong(), anyLong(), anyUuid(), anyInstant(), anyInstant())
            val executor = Executors.newFixedThreadPool(2)

            // when
            val rounds =
                try {
                    expenseIds.map { expenseId ->
                        val deletion = executor.submit<Unit> { deleteExpenseUseCase.delete(trip.adminMemberId, trip.tripId, expenseId) }
                        val acquisition =
                            executor.submit<ExpenseEditLockResult> {
                                acquireExpenseEditLockUseCase.acquire(trip.recorderMemberId, trip.tripId, expenseId)
                            }
                        val deleteFailure =
                            try {
                                deletion.get(20, TimeUnit.SECONDS)
                                null
                            } catch (exception: ExecutionException) {
                                errorCode(exception.cause ?: exception)
                            }
                        val lock =
                            try {
                                Result.success(acquisition.get(20, TimeUnit.SECONDS))
                            } catch (exception: ExecutionException) {
                                Result.failure(exception.cause ?: exception)
                            }
                        val row = findExpenseRow(expenseId)
                        Round(
                            deleted = row["deleted_at"] != null,
                            deleteFailure = deleteFailure,
                            storedLockToken = row["edit_lock_token"] as UUID?,
                            acquiredLockToken = lock.getOrNull()?.lockToken,
                            acquireFailure = lock.exceptionOrNull()?.let(::errorCode),
                        )
                    }
                } finally {
                    executor.shutdownNow()
                }

            // then
            val deletionApplied =
                Round(
                    deleted = true,
                    deleteFailure = null,
                    storedLockToken = null,
                    acquiredLockToken = null,
                    acquireFailure = CommonErrorCode.NOT_FOUND,
                )
            assertSoftly {
                rounds.forEach { round ->
                    if (round.deleted) {
                        round shouldBe deletionApplied
                    } else {
                        round shouldBe
                            Round(
                                deleted = false,
                                deleteFailure = ExpenseErrorCode.EDIT_LOCKED,
                                storedLockToken = round.storedLockToken.shouldNotBeNull(),
                                acquiredLockToken = round.storedLockToken,
                                acquireFailure = null,
                            )
                    }
                }
            }
        }

        test("같은 지출의 삭제가 동시에 오면 한 번만 반영하고 다른 요청은 404를 받는다") {
            // given
            val trip = createTrip()
            val expenseId = insertExpense(trip)
            // 두 요청이 모두 권한 확인을 마친 뒤에 삭제 UPDATE를 함께 실행하도록 맞춥니다.
            val barrier = CyclicBarrier(2)
            doAnswer { invocation ->
                barrier.await(10, TimeUnit.SECONDS)
                invocation.callRealMethod()
            }.`when`(expenseRepository).deleteExpenseIfUnlocked(anyLong(), anyLong(), anyInstant())
            val executor = Executors.newFixedThreadPool(2)

            // when
            val failures =
                try {
                    listOf(trip.adminMemberId, trip.recorderMemberId)
                        .map { memberId -> executor.submit<Unit> { deleteExpenseUseCase.delete(memberId, trip.tripId, expenseId) } }
                        .map { future ->
                            try {
                                future.get(20, TimeUnit.SECONDS)
                                null
                            } catch (exception: ExecutionException) {
                                errorCode(exception.cause ?: exception)
                            }
                        }
                } finally {
                    executor.shutdownNow()
                }

            // then
            val row = findExpenseRow(expenseId)
            assertSoftly {
                failures.count { it == null } shouldBe 1
                failures.filterNotNull() shouldBe listOf<ErrorCode>(CommonErrorCode.NOT_FOUND)
                row["deleted_at"].shouldNotBeNull()
                row["updated_at"] shouldBe row["deleted_at"]
            }
        }
    }

    /** 삭제와 잠금 획득이 겹친 한 번의 결과입니다. 응답과 저장된 지출 행을 함께 담습니다. */
    private data class Round(
        val deleted: Boolean,
        val deleteFailure: ErrorCode?,
        val storedLockToken: UUID?,
        val acquiredLockToken: UUID?,
        val acquireFailure: ErrorCode?,
    )

    private data class Lock(
        val ownerTripMemberId: Long,
        val token: UUID,
        val expiresAt: Instant,
    )

    /** 업무 실패면 오류 코드를 돌려주고, 그 밖의 예외는 원인이 가려지지 않도록 그대로 던집니다. */
    private fun errorCode(failure: Throwable): ErrorCode = (failure as? BusinessException)?.errorCode ?: throw failure

    private fun anyInstant(): Instant = any(Instant::class.java) ?: Instant.EPOCH

    private fun anyUuid(): UUID = any(UUID::class.java) ?: UUID(0, 0)

    private fun findExpenseRow(expenseId: Long): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM expense WHERE id = ?", expenseId)

    /** 품목, 품목별 부담자와 참여자별 부담액 행을 행 ID까지 읽습니다. */
    private fun findDependentRows(expenseId: Long): Map<String, Any?> =
        mapOf(
            "items" to jdbc.queryForList("SELECT * FROM expense_item WHERE expense_id = ? ORDER BY id", expenseId),
            "itemMembers" to jdbc.queryForList("SELECT * FROM expense_item_member WHERE expense_id = ? ORDER BY id", expenseId),
            "shareAmounts" to jdbc.queryForList("SELECT * FROM expense_share_amount WHERE expense_id = ? ORDER BY id", expenseId),
        )

    /** 기록자는 [TripFixture.recorderId], 결제자는 [TripFixture.payerId]인 12,000원 지출을 바로 넣습니다. 수정 시각은 1시간 전으로 둡니다. */
    private fun insertExpense(
        trip: TripFixture,
        splitType: String = "EQUAL",
        lock: Lock? = null,
        settlementId: Long? = null,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO expense (trip_id, idempotency_key, name, split_type, original_amount, discount_amount, amount, currency, " +
                    "paid_date, payer_trip_member_id, created_by_trip_member_id, payment_method, exchange_rate, exchange_rate_source, " +
                    "krw_amount, edit_lock_owner_trip_member_id, edit_lock_token, edit_lock_expires_at, settlement_id, " +
                    "created_at, updated_at) " +
                    "VALUES (?, ?, '식사', ?::split_type, 12000, 0, 12000, 'KRW', '2026-11-05', ?, ?, 'CARD', 1, 'MANUAL', 12000, " +
                    "?, ?, ?, ?, now() - interval '1 hour', now() - interval '1 hour') RETURNING id",
                Long::class.java,
                trip.tripId,
                UUID.randomUUID(),
                splitType,
                trip.payerId,
                trip.recorderId,
                lock?.ownerTripMemberId,
                lock?.token,
                lock?.let { Timestamp.from(it.expiresAt) },
                settlementId,
            ),
        )

    private fun insertItem(
        expenseId: Long,
        name: String,
        amount: Long,
        sortOrder: Int,
        tripMemberIds: List<Long>,
    ) {
        val itemId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO expense_item (expense_id, name, amount, discount_amount, sort_order, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 0, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    expenseId,
                    name,
                    amount,
                    sortOrder,
                ),
            )
        tripMemberIds.forEach {
            jdbc.update(
                "INSERT INTO expense_item_member (expense_item_id, expense_id, trip_member_id, created_at, updated_at) " +
                    "VALUES (?, ?, ?, now(), now())",
                itemId,
                expenseId,
                it,
            )
        }
    }

    private fun insertShareAmount(
        tripId: Long,
        expenseId: Long,
        tripMemberId: Long,
        amount: Long,
    ) {
        jdbc.update(
            "INSERT INTO expense_share_amount (expense_id, trip_id, trip_member_id, amount, krw_amount, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, now(), now())",
            expenseId,
            tripId,
            tripMemberId,
            amount,
            amount,
        )
    }

    private fun insertSettlement(tripId: Long): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO settlement (trip_id, settlement_method, status, created_at, updated_at) " +
                    "VALUES (?, 'SINGLE_CURRENCY', 'DRAFT', now(), now()) RETURNING id",
                Long::class.java,
                tripId,
            ),
        )

    /** 확정된 여행에 관리자, 기록자, 결제자와 나간 회원을 넣습니다. */
    private fun createTrip(): TripFixture {
        val key = UUID.randomUUID()
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, invite_code, " +
                        "idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', '2026-11-05', '2026-11-07', 'JP', 'JPY', 4, 'CONFIRMED', ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    key.toString().replace("-", "").take(20),
                    key,
                ),
            )
        tripIds.add(tripId)
        val adminMemberId = createMember()
        val recorderMemberId = createMember()
        val payerMemberId = createMember()
        val leftMemberId = createMember()
        addTripMember(tripId, leftMemberId, "하준", "MEMBER", "LEFT")
        return TripFixture(
            tripId = tripId,
            adminMemberId = adminMemberId,
            adminId = addTripMember(tripId, adminMemberId, "민지", "ADMIN", "MEMBER"),
            recorderMemberId = recorderMemberId,
            recorderId = addTripMember(tripId, recorderMemberId, "지훈", "MEMBER", "MEMBER"),
            payerMemberId = payerMemberId,
            payerId = addTripMember(tripId, payerMemberId, "서연", "MEMBER", "MEMBER"),
            leftMemberId = leftMemberId,
        )
    }

    private fun createMember(): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(id)
        return id
    }

    private fun addTripMember(
        tripId: Long,
        memberId: Long,
        tripNickname: String,
        role: String,
        status: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?::trip_member_role, ?::trip_member_status, now(), now()) RETURNING id",
                Long::class.java,
                tripId,
                memberId,
                tripNickname,
                role,
                status,
            ),
        )

    private data class TripFixture(
        val tripId: Long,
        val adminMemberId: Long,
        val adminId: Long,
        val recorderMemberId: Long,
        val recorderId: Long,
        val payerMemberId: Long,
        val payerId: Long,
        val leftMemberId: Long,
    )
}
