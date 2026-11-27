package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.global.error.ErrorCode
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.ExpenseDetailResult
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.domain.PaymentMethod
import com.ject.module.expense.domain.RateSource
import com.ject.module.expense.domain.SplitType
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdateExpenseUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var updateExpenseUseCase: UpdateExpenseUseCase

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

        test("잠금을 가진 관리자가 전체 1/N 지출을 품목별 USD 지출로 수정하면 지출, 품목과 부담액을 다시 계산해 저장하고 잠금을 해제하며 기록자는 바꾸지 않는다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val expenseId = insertEqualExpense(trip, lock = Lock(trip.adminId, token, Instant.now().plusSeconds(60)))
            val before = findAudit(expenseId)
            // 최종 결제액 11.00달러를 기록자 6.84, 비로그인 참여자 4.50의 비율로 나눕니다. 남은 1센트는 소수점 이하가 더 큰 비로그인 참여자가 받습니다.
            val command =
                command(
                    name = "Dinner",
                    splitType = SplitType.ITEM,
                    currency = ExpenseCurrency.USD,
                    paidDate = "2026-11-06",
                    paymentMethod = PaymentMethod.TRAVEL_CARD,
                    payer = trip.adminId,
                    originalAmount = "12.34",
                    discountAmount = "0.34",
                    exchangeRate = "1380.55",
                    exchangeRateSource = RateSource.AUTO,
                    items =
                        listOf(
                            CreateExpenseCommand.Item("피자", BigDecimal("10.00"), BigDecimal("1.00"), listOf(trip.recorderId, trip.guestId)),
                            CreateExpenseCommand.Item(null, BigDecimal("2.34"), BigDecimal("0.00"), listOf(trip.recorderId)),
                        ),
                )

            // when
            val result = updateExpenseUseCase.update(trip.adminMemberId, trip.tripId, expenseId, token, command)

            // then
            val after = findAudit(expenseId)
            assertSoftly {
                findExpense(expenseId) shouldBe
                    mapOf(
                        "name" to "Dinner",
                        "split_type" to "ITEM",
                        "original_amount" to 1234L,
                        "discount_amount" to 34L,
                        "amount" to 1100L,
                        "currency" to "USD",
                        "paid_date" to "2026-11-06",
                        "payer_trip_member_id" to trip.adminId,
                        "created_by_trip_member_id" to trip.recorderId,
                        "payment_method" to "TRAVEL_CARD",
                        "exchange_rate" to "1380.55",
                        "exchange_rate_source" to "AUTO",
                        "krw_amount" to 15186L,
                        "edit_lock_owner_trip_member_id" to null,
                        "edit_lock_token" to null,
                        "edit_lock_expires_at" to null,
                        "settlement_id" to null,
                    )
                findItems(expenseId) shouldBe
                    listOf(
                        mapOf("name" to "피자", "amount" to 1000L, "discount_amount" to 100L, "sort_order" to 0),
                        mapOf("name" to null, "amount" to 234L, "discount_amount" to 0L, "sort_order" to 1),
                    )
                findItemMembers(expenseId) shouldBe
                    listOf(listOf(0L, trip.recorderId), listOf(0L, trip.guestId), listOf(1L, trip.recorderId))
                findShareAmounts(expenseId) shouldBe listOf(listOf(trip.recorderId, 663L, 9153L), listOf(trip.guestId, 437L, 6033L))
                after["idempotency_key"] shouldBe before["idempotency_key"]
                after["created_at"] shouldBe before["created_at"]
                (after["updated_at"] as Timestamp).after(before["updated_at"] as Timestamp) shouldBe true
                result.isEditLocked shouldBe false
                result.createdByTripMemberId shouldBe trip.recorderId
                result.amount shouldBe BigDecimal("11.00")
                result.shares.map { it.tripMemberId to it.amount } shouldBe
                    listOf(trip.recorderId to BigDecimal("6.63"), trip.guestId to BigDecimal("4.37"))
            }
        }

        test("품목별 지출을 전체 1/N으로 수정하면 기존 품목과 품목별 부담자를 모두 지우고 새 부담액만 남긴다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val expenseId = insertItemExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)))
            // 10,000원을 세 명이 나누면 1원이 남고, 나머지가 같으므로 여행 참여자 ID가 가장 작은 관리자가 받습니다.
            val command = equal(trip.payerId, "10000.00", listOf(trip.payerId, trip.recorderId, trip.adminId))

            // when
            val result = updateExpenseUseCase.update(trip.recorderMemberId, trip.tripId, expenseId, token, command)

            // then
            assertSoftly {
                findItems(expenseId) shouldBe emptyList()
                findItemMembers(expenseId) shouldBe emptyList()
                findShareAmounts(expenseId) shouldBe
                    listOf(listOf(trip.adminId, 3334L, 3334L), listOf(trip.recorderId, 3333L, 3333L), listOf(trip.payerId, 3333L, 3333L))
                findExpense(expenseId)["split_type"] shouldBe "EQUAL"
                findExpense(expenseId)["amount"] shouldBe 10000L
                findExpense(expenseId)["edit_lock_token"] shouldBe null
                result.items shouldBe emptyList()
            }
        }

        test("토큰이나 소유자가 다르거나 잠금이 만료됐거나 없으면 409 EXPENSE_EDIT_LOCK_INVALID로 거절하고 지출, 부담액과 기존 잠금을 그대로 둔다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val locked = insertEqualExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)))
            val expired = insertEqualExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().minusSeconds(1)))
            val unlocked = insertEqualExpense(trip)
            val attempts =
                mapOf(
                    "다른 토큰" to Attempt(trip.recorderMemberId, locked, UUID.randomUUID()),
                    "다른 소유자" to Attempt(trip.adminMemberId, locked, token),
                    "만료된 잠금" to Attempt(trip.recorderMemberId, expired, token),
                    "잠금 없음" to Attempt(trip.recorderMemberId, unlocked, token),
                )
            val before = attempts.mapValues { snapshot(it.value.expenseId) }
            val command = equal(trip.recorderId, "30000.00", listOf(trip.recorderId))

            // when
            val failures =
                attempts.mapValues { (_, attempt) ->
                    shouldThrow<BusinessException> {
                        updateExpenseUseCase.update(attempt.memberId, trip.tripId, attempt.expenseId, attempt.token, command)
                    }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe attempts.mapValues { ExpenseErrorCode.EDIT_LOCK_INVALID }
                attempts.mapValues { snapshot(it.value.expenseId) } shouldBe before
            }
        }

        test("정산이 점유한 지출이면 토큰이 같은 잠금이 남아 있어도 409 EXPENSE_SETTLEMENT_LOCKED로 거절하고 지출을 바꾸지 않는다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val settlementId = insertSettlement(trip.tripId)
            // 정산 점유는 수정 잠금을 비우지만, 잠금 칼럼이 남은 경우에도 정산 점유 조건만으로 막는지 함께 확인합니다.
            val attempts =
                mapOf(
                    "잠금이 비워진 지출" to insertEqualExpense(trip, settlementId = settlementId),
                    "잠금이 남은 지출" to
                        insertEqualExpense(
                            trip,
                            lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)),
                            settlementId = settlementId,
                        ),
                )
            val before = attempts.mapValues { snapshot(it.value) }
            val command = equal(trip.recorderId, "30000.00", listOf(trip.recorderId))

            // when
            val failures =
                attempts.mapValues { (_, expenseId) ->
                    shouldThrow<BusinessException> {
                        updateExpenseUseCase.update(trip.recorderMemberId, trip.tripId, expenseId, token, command)
                    }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe attempts.mapValues { ExpenseErrorCode.SETTLEMENT_LOCKED }
                attempts.mapValues { snapshot(it.value) } shouldBe before
            }
        }

        test("수정에 성공한 뒤 같은 토큰으로 다시 요청하면 409 EXPENSE_EDIT_LOCK_INVALID로 거절하고 처음 수정한 내용을 그대로 둔다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val expenseId = insertEqualExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)))
            val command = equal(trip.recorderId, "30000.00", listOf(trip.recorderId, trip.guestId))
            updateExpenseUseCase.update(trip.recorderMemberId, trip.tripId, expenseId, token, command)
            val afterFirstUpdate = snapshot(expenseId)

            // when
            val failure =
                shouldThrow<BusinessException> {
                    updateExpenseUseCase.update(trip.recorderMemberId, trip.tripId, expenseId, token, command)
                }

            // then
            assertSoftly {
                failure.errorCode shouldBe ExpenseErrorCode.EDIT_LOCK_INVALID
                snapshot(expenseId) shouldBe afterFirstUpdate
                findShareAmounts(expenseId) shouldBe listOf(listOf(trip.recorderId, 15000L, 15000L), listOf(trip.guestId, 15000L, 15000L))
            }
        }

        test("기록자도 관리자도 아닌 참여자나 나간 회원이 요청하면 403, 삭제됐거나 다른 여행의 지출이면 404로 거절하고 지출을 바꾸지 않는다") {
            // given
            val trip = createTrip()
            val otherTrip = createTrip()
            val token = UUID.randomUUID()
            val lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60))
            val locked = insertEqualExpense(trip, lock = lock)
            val deleted = insertEqualExpense(trip, lock = lock, deleted = true)
            val ofOtherTrip =
                insertEqualExpense(otherTrip, lock = Lock(otherTrip.recorderId, token, Instant.now().plusSeconds(60)))
            val attempts =
                mapOf(
                    "결제자" to Attempt(trip.payerMemberId, locked, token),
                    "나간 회원" to Attempt(trip.leftMemberId, locked, token),
                    "삭제된 지출" to Attempt(trip.recorderMemberId, deleted, token),
                    "다른 여행의 지출" to Attempt(trip.recorderMemberId, ofOtherTrip, token),
                )
            val before = attempts.mapValues { snapshot(it.value.expenseId) }
            val command = equal(trip.recorderId, "30000.00", listOf(trip.recorderId))

            // when
            val failures =
                attempts.mapValues { (_, attempt) ->
                    shouldThrow<BusinessException> {
                        updateExpenseUseCase.update(attempt.memberId, trip.tripId, attempt.expenseId, attempt.token, command)
                    }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe
                    mapOf<String, ErrorCode>(
                        "결제자" to CommonErrorCode.FORBIDDEN,
                        "나간 회원" to CommonErrorCode.FORBIDDEN,
                        "삭제된 지출" to CommonErrorCode.NOT_FOUND,
                        "다른 여행의 지출" to CommonErrorCode.NOT_FOUND,
                    )
                attempts.mapValues { snapshot(it.value.expenseId) } shouldBe before
            }
        }

        test("금액이나 부담자 검증에 실패하면 지출과 수정 잠금을 그대로 두고, 같은 잠금으로 고쳐 보낸 요청은 반영한다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val expenseId = insertEqualExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)))
            val before = snapshot(expenseId)
            val pizza = CreateExpenseCommand.Item("피자", BigDecimal("8000.00"), BigDecimal("0.00"), listOf(trip.recorderId))
            val commands =
                mapOf(
                    "품목 합계가 총액과 다름" to byItem(trip.recorderId, "9000.00", listOf(pizza)),
                    "부담자가 겹침" to equal(trip.recorderId, "9000.00", listOf(trip.recorderId, trip.recorderId)),
                    "전체 1/N에 품목을 보냄" to equal(trip.recorderId, "8000.00", listOf(trip.recorderId)).copy(items = listOf(pizza)),
                    "0 미만인 최종 결제액" to
                        equal(trip.recorderId, "9000.00", listOf(trip.recorderId)).copy(discountAmount = BigDecimal("9001.00")),
                )

            // when
            val failures =
                commands.mapValues { (_, command) ->
                    shouldThrow<BusinessException> {
                        updateExpenseUseCase.update(trip.recorderMemberId, trip.tripId, expenseId, token, command)
                    }.errorCode
                }
            val afterFailures = snapshot(expenseId)
            val corrected =
                updateExpenseUseCase.update(
                    trip.recorderMemberId,
                    trip.tripId,
                    expenseId,
                    token,
                    equal(trip.recorderId, "9000.00", listOf(trip.recorderId)),
                )

            // then
            assertSoftly {
                failures shouldBe
                    mapOf<String, ErrorCode>(
                        "품목 합계가 총액과 다름" to ExpenseErrorCode.AMOUNT_MISMATCH,
                        "부담자가 겹침" to ExpenseErrorCode.INVALID_SHARES,
                        "전체 1/N에 품목을 보냄" to CommonErrorCode.INVALID_REQUEST,
                        "0 미만인 최종 결제액" to CommonErrorCode.INVALID_REQUEST,
                    )
                afterFailures shouldBe before
                corrected.amount shouldBe BigDecimal("9000")
                findShareAmounts(expenseId) shouldBe listOf(listOf(trip.recorderId, 9000L, 9000L))
            }
        }

        test("수정 전 지출의 결제자와 부담자는 여행을 나갔거나 탈퇴했어도 그대로 두고 금액을 수정할 수 있다") {
            // given
            val trip = createTrip()
            val withdrawnId = addTripMember(trip.tripId, createMember(), "도윤", "MEMBER", "WITHDRAWN")
            val token = UUID.randomUUID()
            val expenseId =
                insertEqualExpense(
                    trip,
                    lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)),
                    payerId = trip.leftId,
                    shareTripMemberIds = listOf(trip.recorderId, trip.leftId, withdrawnId),
                )
            val command = equal(trip.leftId, "9000.00", listOf(trip.recorderId, trip.leftId, withdrawnId))

            // when
            updateExpenseUseCase.update(trip.recorderMemberId, trip.tripId, expenseId, token, command)

            // then
            assertSoftly {
                findExpense(expenseId)["payer_trip_member_id"] shouldBe trip.leftId
                findExpense(expenseId)["amount"] shouldBe 9000L
                findShareAmounts(expenseId) shouldBe
                    listOf(listOf(trip.recorderId, 3000L, 3000L), listOf(trip.leftId, 3000L, 3000L), listOf(withdrawnId, 3000L, 3000L))
            }
        }

        test("결제자나 부담자로 지정할 수 없는 참여자를 새로 지정하면 400 EXPENSE_INVALID_SHARES로 거절하고 지출과 수정 잠금을 그대로 둔다") {
            // given
            val trip = createTrip()
            val withdrawnId = addTripMember(trip.tripId, createMember(), "도윤", "MEMBER", "WITHDRAWN")
            val token = UUID.randomUUID()
            val expenseId =
                insertEqualExpense(
                    trip,
                    lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)),
                    payerId = trip.payerId,
                    shareTripMemberIds = listOf(trip.recorderId, trip.payerId),
                )
            val commands =
                mapOf(
                    "나간 회원을 새 부담자로" to equal(trip.payerId, "9000.00", listOf(trip.recorderId, trip.leftId)),
                    "탈퇴 회원을 새 부담자로" to equal(trip.payerId, "9000.00", listOf(trip.recorderId, withdrawnId)),
                    "나간 회원을 새 결제자로" to equal(trip.leftId, "9000.00", listOf(trip.recorderId, trip.payerId)),
                    "비로그인 참여자를 새 결제자로" to equal(trip.guestId, "9000.00", listOf(trip.recorderId, trip.payerId)),
                )
            val before = snapshot(expenseId)

            // when
            val failures =
                commands.mapValues { (_, command) ->
                    shouldThrow<BusinessException> {
                        updateExpenseUseCase.update(trip.recorderMemberId, trip.tripId, expenseId, token, command)
                    }.errorCode
                }

            // then
            assertSoftly {
                failures shouldBe commands.mapValues { ExpenseErrorCode.INVALID_SHARES }
                snapshot(expenseId) shouldBe before
            }
        }

        test("품목 저장이 실패하면 지출 갱신과 기존 품목 삭제를 모두 되돌려 기존 지출, 품목, 부담액과 수정 잠금을 그대로 둔다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val expenseId = insertItemExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)))
            val before = snapshot(expenseId)
            // expense_item.name은 50자까지라 51자 품목명은 지출을 갱신하고 기존 품목을 지운 뒤 품목 저장에서 실패합니다.
            val failingCommand =
                byItem(
                    trip.recorderId,
                    "1000.00",
                    listOf(CreateExpenseCommand.Item("가".repeat(51), BigDecimal("1000.00"), BigDecimal("0.00"), listOf(trip.recorderId))),
                )

            // when
            val failure =
                shouldThrow<DataIntegrityViolationException> {
                    updateExpenseUseCase.update(trip.recorderMemberId, trip.tripId, expenseId, token, failingCommand)
                }

            // then
            assertSoftly {
                failure.message.orEmpty().contains("expense_item") shouldBe true
                snapshot(expenseId) shouldBe before
            }
        }

        test("같은 토큰의 수정 요청이 동시에 오면 하나만 반영하고 다른 요청은 409 EXPENSE_EDIT_LOCK_INVALID를 받는다") {
            // given
            val trip = createTrip()
            val token = UUID.randomUUID()
            val expenseId = insertEqualExpense(trip, lock = Lock(trip.recorderId, token, Instant.now().plusSeconds(60)))
            val commands =
                listOf(
                    equal(trip.recorderId, "10000.00", listOf(trip.recorderId)),
                    equal(trip.recorderId, "20000.00", listOf(trip.recorderId, trip.guestId)),
                )
            // 두 요청이 모두 검증을 마친 뒤에 지출 갱신 UPDATE를 실행하도록 기존 부담자 조회 두 번을 맞춥니다.
            val barrier = CyclicBarrier(2)
            val lookups = AtomicInteger()
            doAnswer { invocation ->
                val result = invocation.callRealMethod()
                if (lookups.incrementAndGet() <= 2) {
                    barrier.await(10, TimeUnit.SECONDS)
                }
                result
            }.`when`(expenseRepository).findShareAmounts(expenseId)
            val executor = Executors.newFixedThreadPool(2)

            // when
            val outcomes =
                try {
                    commands
                        .map { command ->
                            executor.submit<ExpenseDetailResult> {
                                updateExpenseUseCase.update(trip.recorderMemberId, trip.tripId, expenseId, token, command)
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
                failures shouldBe listOf<ErrorCode>(ExpenseErrorCode.EDIT_LOCK_INVALID)
                findExpense(expenseId)["edit_lock_token"] shouldBe null
                // 저장된 지출과 부담액이 성공한 요청 하나의 결과와 같아야 두 요청이 섞이지 않은 것입니다.
                BigDecimal.valueOf(findExpense(expenseId)["amount"] as Long) shouldBe successes.single().amount
                findShareAmounts(expenseId).map { it[0] to BigDecimal.valueOf(it[1]) } shouldBe
                    successes.single().shares.map { it.tripMemberId to it.amount }
            }
        }
    }

    private data class Attempt(
        val memberId: Long,
        val expenseId: Long,
        val token: UUID,
    )

    private data class Lock(
        val ownerTripMemberId: Long,
        val token: UUID,
        val expiresAt: Instant,
    )

    /** 지출 행 전체와 품목, 품목별 부담자, 부담액 행을 행 ID까지 읽습니다. 수정 전과 같으면 지출도 잠금도 바뀌지 않았고 행을 다시 만들지도 않은 것입니다. */
    private fun snapshot(expenseId: Long): Map<String, Any?> =
        mapOf(
            "expense" to jdbc.queryForMap("SELECT * FROM expense WHERE id = ?", expenseId),
            "items" to jdbc.queryForList("SELECT * FROM expense_item WHERE expense_id = ? ORDER BY id", expenseId),
            "itemMembers" to jdbc.queryForList("SELECT * FROM expense_item_member WHERE expense_id = ? ORDER BY id", expenseId),
            "shareAmounts" to jdbc.queryForList("SELECT * FROM expense_share_amount WHERE expense_id = ? ORDER BY id", expenseId),
        )

    private fun findExpense(expenseId: Long): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT name, split_type::text AS split_type, original_amount, discount_amount, amount, currency::text AS currency, " +
                "paid_date::text AS paid_date, payer_trip_member_id, created_by_trip_member_id, payment_method::text AS payment_method, " +
                "trim_scale(exchange_rate)::text AS exchange_rate, exchange_rate_source::text AS exchange_rate_source, krw_amount, " +
                "edit_lock_owner_trip_member_id, edit_lock_token, edit_lock_expires_at, settlement_id FROM expense WHERE id = ?",
            expenseId,
        )

    private fun findAudit(expenseId: Long): Map<String, Any?> =
        jdbc.queryForMap("SELECT idempotency_key, created_at, updated_at FROM expense WHERE id = ?", expenseId)

    private fun findItems(expenseId: Long): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT name, amount, discount_amount, sort_order FROM expense_item WHERE expense_id = ? ORDER BY sort_order",
            expenseId,
        )

    /** 품목의 표시 순서와 부담자의 여행 참여자 ID입니다. */
    private fun findItemMembers(expenseId: Long): List<List<Long>> =
        jdbc.query(
            "SELECT i.sort_order, m.trip_member_id FROM expense_item_member m JOIN expense_item i ON i.id = m.expense_item_id " +
                "WHERE m.expense_id = ? ORDER BY i.sort_order, m.trip_member_id",
            { rs, _ -> listOf(rs.getLong(1), rs.getLong(2)) },
            expenseId,
        )

    private fun findShareAmounts(expenseId: Long): List<List<Long>> =
        jdbc.query(
            "SELECT trip_member_id, amount, krw_amount FROM expense_share_amount WHERE expense_id = ? ORDER BY trip_member_id",
            { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3)) },
            expenseId,
        )

    /** 기록자가 등록한 12,000원 전체 1/N KRW 지출을 부담액과 함께 바로 넣습니다. 수정 시각은 1시간 전으로 둡니다. */
    private fun insertEqualExpense(
        trip: TripFixture,
        lock: Lock? = null,
        settlementId: Long? = null,
        deleted: Boolean = false,
        payerId: Long = trip.payerId,
        shareTripMemberIds: List<Long> = listOf(trip.recorderId, trip.payerId),
    ): Long {
        val expenseId = insertExpense(trip, "EQUAL", amount = 12000, payerId, lock, settlementId, deleted)
        shareTripMemberIds.forEach { insertShareAmount(trip.tripId, expenseId, it, 12000L / shareTripMemberIds.size) }
        return expenseId
    }

    /** 피자 8,000원(상품 할인 2,000원)을 기록자와 결제자가, 음료 4,000원을 기록자가 부담하는 품목별 KRW 지출을 바로 넣습니다. */
    private fun insertItemExpense(
        trip: TripFixture,
        lock: Lock,
    ): Long {
        val expenseId = insertExpense(trip, "ITEM", amount = 10000, trip.payerId, lock, settlementId = null, deleted = false)
        insertItem(expenseId, "피자", 8000, 2000, 0, listOf(trip.recorderId, trip.payerId))
        insertItem(expenseId, "음료", 4000, 0, 1, listOf(trip.recorderId))
        insertShareAmount(trip.tripId, expenseId, trip.recorderId, 7000)
        insertShareAmount(trip.tripId, expenseId, trip.payerId, 3000)
        return expenseId
    }

    private fun insertExpense(
        trip: TripFixture,
        splitType: String,
        amount: Long,
        payerId: Long,
        lock: Lock?,
        settlementId: Long?,
        deleted: Boolean,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO expense (trip_id, idempotency_key, name, split_type, original_amount, discount_amount, amount, currency, " +
                    "paid_date, payer_trip_member_id, created_by_trip_member_id, payment_method, exchange_rate, exchange_rate_source, " +
                    "krw_amount, edit_lock_owner_trip_member_id, edit_lock_token, edit_lock_expires_at, settlement_id, deleted_at, " +
                    "created_at, updated_at) " +
                    "VALUES (?, ?, '식사', ?::split_type, 12000, 0, ?, 'KRW', '2026-11-05', ?, ?, 'CARD', 1, 'MANUAL', ?, ?, ?, ?, ?, " +
                    "?, now() - interval '1 hour', now() - interval '1 hour') RETURNING id",
                Long::class.java,
                trip.tripId,
                UUID.randomUUID(),
                splitType,
                amount,
                payerId,
                trip.recorderId,
                amount,
                lock?.ownerTripMemberId,
                lock?.token,
                lock?.let { Timestamp.from(it.expiresAt) },
                settlementId,
                if (deleted) Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))) else null,
            ),
        )

    private fun insertItem(
        expenseId: Long,
        name: String,
        amount: Long,
        discountAmount: Long,
        sortOrder: Int,
        tripMemberIds: List<Long>,
    ) {
        val itemId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO expense_item (expense_id, name, amount, discount_amount, sort_order, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    expenseId,
                    name,
                    amount,
                    discountAmount,
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

    /** 확정된 여행에 관리자, 기록자, 결제자, 비로그인 참여자와 나간 회원을 이 순서로 넣습니다. 여행 참여자 ID도 이 순서로 커집니다. */
    private fun createTrip(): TripFixture {
        val key = UUID.randomUUID()
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, invite_code, " +
                        "idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', '2026-11-05', '2026-11-07', 'JP', 'JPY', 6, 'CONFIRMED', ?, ?, now(), now()) RETURNING id",
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
        return TripFixture(
            tripId = tripId,
            adminMemberId = adminMemberId,
            adminId = addTripMember(tripId, adminMemberId, "민지", "ADMIN", "MEMBER"),
            recorderMemberId = recorderMemberId,
            recorderId = addTripMember(tripId, recorderMemberId, "지훈", "MEMBER", "MEMBER"),
            payerMemberId = payerMemberId,
            payerId = addTripMember(tripId, payerMemberId, "서연", "MEMBER", "MEMBER"),
            guestId = addTripMember(tripId, null, "하은", "MEMBER", "GUEST"),
            leftMemberId = leftMemberId,
            leftId = addTripMember(tripId, leftMemberId, "하준", "MEMBER", "LEFT"),
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
        memberId: Long?,
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

    private fun equal(
        payer: Long,
        originalAmount: String,
        tripMemberIds: List<Long>,
    ): CreateExpenseCommand =
        command(
            name = "저녁 식사",
            splitType = SplitType.EQUAL,
            currency = ExpenseCurrency.KRW,
            paidDate = "2026-11-05",
            paymentMethod = PaymentMethod.CARD,
            payer = payer,
            originalAmount = originalAmount,
            discountAmount = "0.00",
            exchangeRate = "1.00",
            exchangeRateSource = RateSource.MANUAL,
            tripMemberIds = tripMemberIds,
        )

    private fun byItem(
        payer: Long,
        originalAmount: String,
        items: List<CreateExpenseCommand.Item>,
    ): CreateExpenseCommand =
        command(
            name = "저녁 식사",
            splitType = SplitType.ITEM,
            currency = ExpenseCurrency.KRW,
            paidDate = "2026-11-05",
            paymentMethod = PaymentMethod.CARD,
            payer = payer,
            originalAmount = originalAmount,
            discountAmount = "0.00",
            exchangeRate = "1.00",
            exchangeRateSource = RateSource.MANUAL,
            items = items,
        )

    private fun command(
        name: String,
        splitType: SplitType,
        currency: ExpenseCurrency,
        paidDate: String,
        paymentMethod: PaymentMethod,
        payer: Long,
        originalAmount: String,
        discountAmount: String,
        exchangeRate: String,
        exchangeRateSource: RateSource,
        tripMemberIds: List<Long>? = null,
        items: List<CreateExpenseCommand.Item>? = null,
    ): CreateExpenseCommand =
        CreateExpenseCommand(
            name = name,
            splitType = splitType,
            currency = currency,
            paidDate = LocalDate.parse(paidDate),
            paymentMethod = paymentMethod,
            payerTripMemberId = payer,
            originalAmount = BigDecimal(originalAmount),
            discountAmount = BigDecimal(discountAmount),
            exchangeRate = BigDecimal(exchangeRate),
            exchangeRateSource = exchangeRateSource,
            tripMemberIds = tripMemberIds,
            items = items,
        )

    private data class TripFixture(
        val tripId: Long,
        val adminMemberId: Long,
        val adminId: Long,
        val recorderMemberId: Long,
        val recorderId: Long,
        val payerMemberId: Long,
        val payerId: Long,
        val guestId: Long,
        val leftMemberId: Long,
        val leftId: Long,
    )
}
