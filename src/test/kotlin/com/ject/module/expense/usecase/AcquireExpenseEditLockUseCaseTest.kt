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
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AcquireExpenseEditLockUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var acquireExpenseEditLockUseCase: AcquireExpenseEditLockUseCase

    @MockitoSpyBean
    private lateinit var expenseRepository: ExpenseRepository

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM expense WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM settlement WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("기록자가 잠금을 걸면 새 토큰과 5분 뒤(초 단위로 내림) 만료 시각을 저장하고 지출의 수정 시각은 바꾸지 않는다") {
            // given
            val trip = createTrip()
            val expenseId = insertExpense(trip)
            val updatedAt = findLock(expenseId)["updated_at"]
            val before = Instant.now()

            // when
            val lock = acquireExpenseEditLockUseCase.acquire(trip.recorderMemberId, trip.tripId, expenseId)

            // then
            val after = Instant.now()
            assertSoftly {
                lock.expiresAt shouldBe lock.expiresAt.truncatedTo(ChronoUnit.SECONDS)
                lock.expiresAt shouldBeGreaterThan before.plus(ExpenseEditLockResult.DURATION).minusSeconds(1)
                lock.expiresAt shouldBeLessThanOrEqualTo after.plus(ExpenseEditLockResult.DURATION)
                findLock(expenseId) shouldBe
                    mapOf(
                        "edit_lock_owner_trip_member_id" to trip.recorderId,
                        "edit_lock_token" to lock.lockToken,
                        "edit_lock_expires_at" to Timestamp.from(lock.expiresAt),
                        "updated_at" to updatedAt,
                    )
            }
        }

        test("기록자가 아니어도 여행 관리자는 잠금을 걸 수 있고, 결제자이거나 다른 참여자이면 403으로 거절한다") {
            // given
            val trip = createTrip()
            val adminTarget = insertExpense(trip)
            val payerTarget = insertExpense(trip)

            // when
            val adminLock = acquireExpenseEditLockUseCase.acquire(trip.adminMemberId, trip.tripId, adminTarget)
            val payerFailure =
                shouldThrow<BusinessException> { acquireExpenseEditLockUseCase.acquire(trip.payerMemberId, trip.tripId, payerTarget) }

            // then
            assertSoftly {
                findLock(adminTarget)["edit_lock_owner_trip_member_id"] shouldBe trip.adminId
                findLock(adminTarget)["edit_lock_token"] shouldBe adminLock.lockToken
                payerFailure.errorCode shouldBe CommonErrorCode.FORBIDDEN
                findLock(payerTarget)["edit_lock_token"] shouldBe null
            }
        }

        test("만료되지 않은 잠금이 있으면 다른 회원이든 같은 회원이든 409 EXPENSE_EDIT_LOCKED로 거절하고 기존 잠금을 유지한다") {
            // given
            val trip = createTrip()
            val expenseId = insertExpense(trip)
            val lock = acquireExpenseEditLockUseCase.acquire(trip.recorderMemberId, trip.tripId, expenseId)

            // when
            val failures =
                listOf(trip.adminMemberId, trip.recorderMemberId).map {
                    shouldThrow<BusinessException> { acquireExpenseEditLockUseCase.acquire(it, trip.tripId, expenseId) }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe listOf(ExpenseErrorCode.EDIT_LOCKED, ExpenseErrorCode.EDIT_LOCKED)
                findLock(expenseId)["edit_lock_owner_trip_member_id"] shouldBe trip.recorderId
                findLock(expenseId)["edit_lock_token"] shouldBe lock.lockToken
            }
        }

        test("다른 회원의 잠금이라도 만료됐으면 새 토큰의 잠금으로 바꾼다") {
            // given
            val trip = createTrip()
            val expiredToken = UUID.randomUUID()
            val expenseId = insertExpense(trip, lock = Lock(trip.recorderId, expiredToken, Instant.now().minusSeconds(1)))

            // when
            val lock = acquireExpenseEditLockUseCase.acquire(trip.adminMemberId, trip.tripId, expenseId)

            // then
            assertSoftly {
                (lock.lockToken == expiredToken) shouldBe false
                findLock(expenseId)["edit_lock_owner_trip_member_id"] shouldBe trip.adminId
                findLock(expenseId)["edit_lock_token"] shouldBe lock.lockToken
            }
        }

        test("정산이 점유한 지출이면 409 EXPENSE_SETTLEMENT_LOCKED로 거절하고 잠금을 걸지 않는다") {
            // given
            val trip = createTrip()
            val expenseId = insertExpense(trip, settlementId = insertSettlement(trip.tripId))

            // when
            val failure =
                shouldThrow<BusinessException> { acquireExpenseEditLockUseCase.acquire(trip.recorderMemberId, trip.tripId, expenseId) }

            // then
            assertSoftly {
                failure.errorCode shouldBe ExpenseErrorCode.SETTLEMENT_LOCKED
                findLock(expenseId)["edit_lock_token"] shouldBe null
            }
        }

        test("여행이나 지출이 없거나 삭제됐거나 다른 여행의 지출이면 404, 참여 중인 회원이 아니면 403으로 거절한다") {
            // given
            val trip = createTrip()
            val otherTrip = createTrip()
            val expenseId = insertExpense(trip)
            val deletedExpenseId = insertExpense(trip, deleted = true)
            val otherTripExpenseId = insertExpense(otherTrip)

            // when
            val failures =
                mapOf(
                    "없는 여행" to Triple(trip.recorderMemberId, Long.MAX_VALUE, expenseId),
                    "없는 지출" to Triple(trip.recorderMemberId, trip.tripId, Long.MAX_VALUE),
                    "삭제된 지출" to Triple(trip.recorderMemberId, trip.tripId, deletedExpenseId),
                    "다른 여행의 지출" to Triple(trip.adminMemberId, trip.tripId, otherTripExpenseId),
                    "나간 회원" to Triple(trip.leftMemberId, trip.tripId, expenseId),
                    "다른 여행의 기록자" to Triple(otherTrip.recorderMemberId, trip.tripId, expenseId),
                ).mapValues { (_, request) ->
                    shouldThrow<BusinessException> {
                        acquireExpenseEditLockUseCase.acquire(request.first, request.second, request.third)
                    }.errorCode
                }

            // then
            failures shouldBe
                mapOf(
                    "없는 여행" to CommonErrorCode.NOT_FOUND,
                    "없는 지출" to CommonErrorCode.NOT_FOUND,
                    "삭제된 지출" to CommonErrorCode.NOT_FOUND,
                    "다른 여행의 지출" to CommonErrorCode.NOT_FOUND,
                    "나간 회원" to CommonErrorCode.FORBIDDEN,
                    "다른 여행의 기록자" to CommonErrorCode.FORBIDDEN,
                )
        }

        test("기록자와 관리자가 동시에 잠금을 걸면 한 명만 성공하고 다른 요청은 409 EXPENSE_EDIT_LOCKED를 받는다") {
            // given
            val trip = createTrip()
            val expenseId = insertExpense(trip)
            // 두 요청이 모두 권한 확인을 마친 뒤에 잠금 UPDATE를 실행하도록 첫 지출 조회 두 번을 맞춥니다.
            val barrier = CyclicBarrier(2)
            val lookups = AtomicInteger()
            doAnswer { invocation ->
                val result = invocation.callRealMethod()
                if (lookups.incrementAndGet() <= 2) {
                    barrier.await(10, TimeUnit.SECONDS)
                }
                result
            }.`when`(expenseRepository).findExpense(expenseId)
            val executor = Executors.newFixedThreadPool(2)

            // when
            val outcomes =
                try {
                    listOf(trip.recorderMemberId, trip.adminMemberId)
                        .map { memberId ->
                            executor.submit<ExpenseEditLockResult> {
                                acquireExpenseEditLockUseCase.acquire(
                                    memberId,
                                    trip.tripId,
                                    expenseId,
                                )
                            }
                        }.map { future ->
                            try {
                                Result.success(future.get(20, TimeUnit.SECONDS))
                            } catch (exception: ExecutionException) {
                                Result.failure(exception.cause ?: exception)
                            }
                        }
                } finally {
                    executor.shutdownNow()
                }

            // then
            val successes = outcomes.mapNotNull { it.getOrNull() }
            val failures = outcomes.mapNotNull { (it.exceptionOrNull() as? BusinessException)?.errorCode }
            assertSoftly {
                successes.size shouldBe 1
                failures shouldBe listOf<ErrorCode>(ExpenseErrorCode.EDIT_LOCKED)
                findLock(expenseId)["edit_lock_token"] shouldBe successes.single().lockToken
            }
        }
    }

    private fun findLock(expenseId: Long): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT edit_lock_owner_trip_member_id, edit_lock_token, edit_lock_expires_at, updated_at FROM expense WHERE id = ?",
            expenseId,
        )

    private data class Lock(
        val ownerTripMemberId: Long,
        val token: UUID,
        val expiresAt: Instant,
    )

    /** 기록자는 [TripFixture.recorderId], 결제자는 [TripFixture.payerId]인 지출을 바로 넣습니다. 수정 시각은 1시간 전으로 둡니다. */
    private fun insertExpense(
        trip: TripFixture,
        lock: Lock? = null,
        settlementId: Long? = null,
        deleted: Boolean = false,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO expense (trip_id, idempotency_key, name, split_type, original_amount, discount_amount, amount, currency, " +
                    "paid_date, payer_trip_member_id, created_by_trip_member_id, payment_method, exchange_rate, exchange_rate_source, " +
                    "krw_amount, edit_lock_owner_trip_member_id, edit_lock_token, edit_lock_expires_at, settlement_id, deleted_at, " +
                    "created_at, updated_at) " +
                    "VALUES (?, ?, '식사', 'EQUAL', 12000, 0, 12000, 'KRW', '2026-11-05', ?, ?, 'CARD', 1, 'MANUAL', 12000, ?, ?, ?, ?, " +
                    "?, now() - interval '1 hour', now() - interval '1 hour') RETURNING id",
                Long::class.java,
                trip.tripId,
                UUID.randomUUID(),
                trip.payerId,
                trip.recorderId,
                lock?.ownerTripMemberId,
                lock?.token,
                lock?.let { Timestamp.from(it.expiresAt) },
                settlementId,
                if (deleted) Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))) else null,
            ),
        )

    private fun insertSettlement(tripId: Long): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO settlement (trip_id, settlement_method, status, created_at, updated_at) " +
                    "VALUES (?, 'SINGLE_CURRENCY', 'DRAFT', now(), now()) RETURNING id",
                Long::class.java,
                tripId,
            ),
        )

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
