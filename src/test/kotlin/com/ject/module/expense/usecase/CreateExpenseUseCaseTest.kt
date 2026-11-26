package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
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
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CreateExpenseUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var createExpenseUseCase: CreateExpenseUseCase

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
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("전체 1/N 지출을 등록하면 요청한 회원을 기록자로 지출과 참여자별 부담액을 저장하고 품목은 만들지 않는다") {
            // given
            val trip = confirmedTrip()
            val command =
                equal(payer = trip.adminId, originalAmount = "10000.00", tripMemberIds = listOf(trip.guestId, trip.memberId, trip.adminId))

            // when
            val result = createExpenseUseCase.create(trip.adminMemberId, trip.tripId, UUID.randomUUID(), command)

            // then
            assertSoftly {
                result.tripId shouldBe trip.tripId
                result.splitType shouldBe SplitType.EQUAL
                result.createdByTripMemberId shouldBe trip.adminId
                result.amount shouldBe BigDecimal("10000")
                result.amountKrw shouldBe BigDecimal("10000")
                result.isEditLocked shouldBe false
                result.settlementId shouldBe null
                result.exchangeRateProvider shouldBe null
                result.items shouldBe emptyList()
                result.shares shouldBe
                    listOf(
                        ExpenseDetailResult.Share(trip.adminId, BigDecimal("3334"), BigDecimal("3334")),
                        ExpenseDetailResult.Share(trip.memberId, BigDecimal("3333"), BigDecimal("3333")),
                        ExpenseDetailResult.Share(trip.guestId, BigDecimal("3333"), BigDecimal("3333")),
                    )
                findExpense(result.expenseId) shouldBe
                    mapOf(
                        "split_type" to "EQUAL",
                        "original_amount" to 10000L,
                        "discount_amount" to 0L,
                        "amount" to 10000L,
                        "krw_amount" to 10000L,
                        "created_by_trip_member_id" to trip.adminId,
                        "exchange_rate_provider" to null,
                    )
                countRows("expense_item", result.expenseId) shouldBe 0
                findShareAmounts(result.expenseId) shouldBe
                    listOf(listOf(trip.adminId, 3334L, 3334L), listOf(trip.memberId, 3333L, 3333L), listOf(trip.guestId, 3333L, 3333L))
            }
        }

        test("품목별 JPY 지출을 등록하면 품목을 요청 순서대로 저장하고 상품 할인과 전체 할인을 반영한 부담액과 원화 부담액을 저장한다") {
            // given
            val trip = confirmedTrip()
            val command =
                command(
                    splitType = SplitType.ITEM,
                    currency = ExpenseCurrency.JPY,
                    payer = trip.memberId,
                    originalAmount = "1200.00",
                    discountAmount = "100.00",
                    exchangeRate = "9.12",
                    tripMemberIds = null,
                    items =
                        listOf(
                            CreateExpenseCommand.Item(
                                "피자",
                                BigDecimal("800.00"),
                                BigDecimal("200.00"),
                                listOf(trip.memberId, trip.adminId),
                            ),
                            CreateExpenseCommand.Item(null, BigDecimal("400.00"), BigDecimal("0.00"), listOf(trip.adminId)),
                        ),
                )

            // when
            val result = createExpenseUseCase.create(trip.memberMemberId, trip.tripId, UUID.randomUUID(), command)

            // then
            // 최종 결제액 900엔을 관리자 700엔, 참여자 300엔 비율(7:3)로 나눕니다. 원화 환산액은 900 × 9.12 = 8208원입니다.
            assertSoftly {
                result.splitType shouldBe SplitType.ITEM
                result.createdByTripMemberId shouldBe trip.memberId
                result.amount shouldBe BigDecimal("900")
                result.amountKrw shouldBe BigDecimal("8208")
                result.items.map { listOf(it.name, it.amount, it.discountAmount, it.sortOrder, it.tripMemberIds) } shouldBe
                    listOf(
                        listOf("피자", BigDecimal("800"), BigDecimal("200"), 0, listOf(trip.adminId, trip.memberId)),
                        listOf(null, BigDecimal("400"), BigDecimal("0"), 1, listOf(trip.adminId)),
                    )
                result.shares shouldBe
                    listOf(
                        ExpenseDetailResult.Share(trip.adminId, BigDecimal("630"), BigDecimal("5746")),
                        ExpenseDetailResult.Share(trip.memberId, BigDecimal("270"), BigDecimal("2462")),
                    )
                countRows("expense_item", result.expenseId) shouldBe 2
                countRows("expense_item_member", result.expenseId) shouldBe 3
                findShareAmounts(result.expenseId) shouldBe listOf(listOf(trip.adminId, 630L, 5746L), listOf(trip.memberId, 270L, 2462L))
            }
        }

        test("같은 회원이 같은 Idempotency-Key로 다시 요청하면 지출을 새로 만들지 않고 처음 만든 지출을 반환한다") {
            // given
            val trip = confirmedTrip()
            val idempotencyKey = UUID.randomUUID()
            val first =
                createExpenseUseCase.create(
                    trip.adminMemberId,
                    trip.tripId,
                    idempotencyKey,
                    equal(trip.adminId, "12000.00", listOf(trip.adminId)),
                )

            // when
            val second =
                createExpenseUseCase.create(
                    trip.adminMemberId,
                    trip.tripId,
                    idempotencyKey,
                    equal(trip.adminId, "500.00", listOf(trip.memberId)),
                )

            // then
            assertSoftly {
                second shouldBe first
                countExpenses(idempotencyKey) shouldBe 1
            }
        }

        test("다른 회원이 쓴 Idempotency-Key이거나 그 키로 만든 지출이 삭제됐으면 409 EXPENSE_IDEMPOTENCY_KEY_REUSED로 거절한다") {
            // given
            val trip = confirmedTrip()
            val otherMemberKey = UUID.randomUUID()
            val deletedKey = UUID.randomUUID()
            val command = equal(trip.adminId, "12000.00", listOf(trip.adminId))
            createExpenseUseCase.create(trip.adminMemberId, trip.tripId, otherMemberKey, command)
            val deleted = createExpenseUseCase.create(trip.adminMemberId, trip.tripId, deletedKey, command)
            jdbc.update("UPDATE expense SET deleted_at = now() WHERE id = ?", deleted.expenseId)

            // when
            val failures =
                mapOf(
                    "다른 회원이 쓴 키" to
                        shouldThrow<BusinessException> {
                            createExpenseUseCase.create(
                                trip.memberMemberId,
                                trip.tripId,
                                otherMemberKey,
                                command,
                            )
                        },
                    "삭제된 지출의 키" to
                        shouldThrow<BusinessException> {
                            createExpenseUseCase.create(
                                trip.adminMemberId,
                                trip.tripId,
                                deletedKey,
                                command,
                            )
                        },
                )

            // then
            assertSoftly {
                failures.mapValues { it.value.errorCode } shouldBe failures.mapValues { ExpenseErrorCode.IDEMPOTENCY_KEY_REUSED }
                countExpenses(otherMemberKey) shouldBe 1
                countExpenses(deletedKey) shouldBe 1
            }
        }

        test("같은 Idempotency-Key의 요청이 동시에 와도 지출은 한 번만 저장하고 모든 요청이 같은 지출을 받는다") {
            // given
            val trip = confirmedTrip()
            val idempotencyKey = UUID.randomUUID()
            // 두 요청이 모두 기존 지출 없음을 확인한 뒤에 저장하도록 첫 조회 두 번을 맞춥니다.
            val barrier = CyclicBarrier(2)
            val lookups = AtomicInteger()
            doAnswer { invocation ->
                val result = invocation.callRealMethod()
                if (lookups.incrementAndGet() <= 2) {
                    barrier.await(10, TimeUnit.SECONDS)
                }
                result
            }.`when`(expenseRepository).findExpenseByIdempotencyKey(idempotencyKey)
            val command = equal(trip.adminId, "12000.00", listOf(trip.adminId, trip.memberId))
            val executor = Executors.newFixedThreadPool(2)

            // when
            val results =
                try {
                    List(2) {
                        executor.submit<ExpenseDetailResult> {
                            createExpenseUseCase.create(
                                trip.adminMemberId,
                                trip.tripId,
                                idempotencyKey,
                                command,
                            )
                        }
                    }.map { it.get(30, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }

            // then
            assertSoftly {
                // 세 번째 조회는 늦은 요청이 고유키 유니크 제약 위반으로 롤백된 뒤 먼저 저장된 지출을 다시 찾는 조회입니다.
                lookups.get() shouldBe 3
                countExpenses(idempotencyKey) shouldBe 1
                results[0] shouldBe results[1]
                countRows("expense_share_amount", results[0].expenseId) shouldBe 2
            }
        }

        test("확정 전 여행이면 409 EXPENSE_TRIP_NOT_CONFIRMED로 거절하고 지출을 저장하지 않는다") {
            // given
            val trip = confirmedTrip(status = "UNCONFIRMED")
            val idempotencyKey = UUID.randomUUID()

            // when
            val failure =
                shouldThrow<BusinessException> {
                    createExpenseUseCase.create(
                        trip.adminMemberId,
                        trip.tripId,
                        idempotencyKey,
                        equal(trip.adminId, "12000.00", listOf(trip.adminId)),
                    )
                }

            // then
            assertSoftly {
                failure.errorCode shouldBe ExpenseErrorCode.TRIP_NOT_CONFIRMED
                countExpenses(idempotencyKey) shouldBe 0
            }
        }

        test("여행이 없거나 삭제됐으면 404, 참여하지 않았거나 나간 회원이 요청하면 403으로 거절한다") {
            // given
            val trip = confirmedTrip()
            val deletedTrip = confirmedTrip()
            jdbc.update("UPDATE trip SET deleted_at = now() WHERE id = ?", deletedTrip.tripId)
            val outsiderMemberId = createMember()
            val command = equal(trip.adminId, "12000.00", listOf(trip.adminId))

            // when
            val failures =
                mapOf(
                    "없는 여행" to
                        shouldThrow<BusinessException> {
                            createExpenseUseCase.create(
                                trip.adminMemberId,
                                Long.MAX_VALUE,
                                UUID.randomUUID(),
                                command,
                            )
                        },
                    "삭제된 여행" to
                        shouldThrow<BusinessException> {
                            createExpenseUseCase.create(
                                deletedTrip.adminMemberId,
                                deletedTrip.tripId,
                                UUID.randomUUID(),
                                command,
                            )
                        },
                    "참여하지 않은 회원" to
                        shouldThrow<BusinessException> {
                            createExpenseUseCase.create(
                                outsiderMemberId,
                                trip.tripId,
                                UUID.randomUUID(),
                                command,
                            )
                        },
                    "나간 회원" to
                        shouldThrow<BusinessException> {
                            createExpenseUseCase.create(
                                trip.leftMemberId,
                                trip.tripId,
                                UUID.randomUUID(),
                                command,
                            )
                        },
                )

            // then
            assertSoftly {
                failures.mapValues { it.value.errorCode } shouldBe
                    mapOf(
                        "없는 여행" to CommonErrorCode.NOT_FOUND,
                        "삭제된 여행" to CommonErrorCode.NOT_FOUND,
                        "참여하지 않은 회원" to CommonErrorCode.FORBIDDEN,
                        "나간 회원" to CommonErrorCode.FORBIDDEN,
                    )
                countTripExpenses(trip.tripId) shouldBe 0
            }
        }

        test("결제자나 부담자로 지정할 수 없는 참여자가 있으면 400 EXPENSE_INVALID_SHARES로 거절하고 지출을 저장하지 않는다") {
            // given
            val trip = confirmedTrip()
            val otherTrip = confirmedTrip()
            val commands =
                mapOf(
                    "비로그인 참여자인 결제자" to equal(trip.guestId, "12000.00", listOf(trip.adminId)),
                    "나간 회원인 결제자" to equal(trip.leftId, "12000.00", listOf(trip.adminId)),
                    "다른 여행의 참여자인 결제자" to equal(otherTrip.adminId, "12000.00", listOf(trip.adminId)),
                    "나간 회원인 부담자" to equal(trip.adminId, "12000.00", listOf(trip.adminId, trip.leftId)),
                    "다른 여행의 참여자인 부담자" to equal(trip.adminId, "12000.00", listOf(otherTrip.guestId)),
                    "부담자 없음" to equal(trip.adminId, "12000.00", emptyList()),
                    "겹친 부담자" to equal(trip.adminId, "12000.00", listOf(trip.adminId, trip.adminId)),
                    "품목 부담자 없음" to
                        byItem(
                            trip.adminId,
                            "1000.00",
                            listOf(CreateExpenseCommand.Item(null, BigDecimal("1000.00"), BigDecimal("0.00"), emptyList())),
                        ),
                    "나간 회원인 품목 부담자" to
                        byItem(
                            trip.adminId,
                            "1000.00",
                            listOf(CreateExpenseCommand.Item(null, BigDecimal("1000.00"), BigDecimal("0.00"), listOf(trip.leftId))),
                        ),
                )

            // when
            val failures =
                commands.mapValues {
                    shouldThrow<BusinessException> {
                        createExpenseUseCase.create(
                            trip.adminMemberId,
                            trip.tripId,
                            UUID.randomUUID(),
                            it.value,
                        )
                    }
                }

            // then
            assertSoftly {
                failures.mapValues { it.value.errorCode } shouldBe commands.mapValues { ExpenseErrorCode.INVALID_SHARES }
                countTripExpenses(trip.tripId) shouldBe 0
            }
        }

        test("분배 방식에 맞지 않는 부담자 입력은 400 COMMON_INVALID_REQUEST로 거절한다") {
            // given
            val trip = confirmedTrip()
            val item = CreateExpenseCommand.Item(null, BigDecimal("1000.00"), BigDecimal("0.00"), listOf(trip.adminId))
            val commands =
                mapOf(
                    "전체 1/N인데 tripMemberIds 없음" to equal(trip.adminId, "1000.00", listOf(trip.adminId)).copy(tripMemberIds = null),
                    "전체 1/N인데 items 있음" to equal(trip.adminId, "1000.00", listOf(trip.adminId)).copy(items = listOf(item)),
                    "품목별인데 items 없음" to byItem(trip.adminId, "1000.00", listOf(item)).copy(items = null),
                    "품목별인데 items 비어 있음" to byItem(trip.adminId, "1000.00", emptyList()),
                    "품목별인데 tripMemberIds 있음" to byItem(trip.adminId, "1000.00", listOf(item)).copy(tripMemberIds = listOf(trip.adminId)),
                )

            // when
            val failures =
                commands.mapValues {
                    shouldThrow<BusinessException> {
                        createExpenseUseCase.create(
                            trip.adminMemberId,
                            trip.tripId,
                            UUID.randomUUID(),
                            it.value,
                        )
                    }
                }

            // then
            assertSoftly {
                failures.mapValues { it.value.errorCode } shouldBe commands.mapValues { CommonErrorCode.INVALID_REQUEST }
                countTripExpenses(trip.tripId) shouldBe 0
            }
        }

        test("품목 금액 합계가 할인 전 총액과 다르면 400 EXPENSE_AMOUNT_MISMATCH로 거절하고 지출을 저장하지 않는다") {
            // given
            val trip = confirmedTrip()
            val command =
                byItem(
                    trip.adminId,
                    "12000.00",
                    listOf(CreateExpenseCommand.Item(null, BigDecimal("11000.00"), BigDecimal("0.00"), listOf(trip.adminId))),
                )

            // when
            val failure =
                shouldThrow<BusinessException> { createExpenseUseCase.create(trip.adminMemberId, trip.tripId, UUID.randomUUID(), command) }

            // then
            assertSoftly {
                failure.errorCode shouldBe ExpenseErrorCode.AMOUNT_MISMATCH
                countTripExpenses(trip.tripId) shouldBe 0
            }
        }

        test("품목 저장이 실패하면 지출과 고유키도 저장하지 않고, 같은 키로 다시 요청하면 지출을 만든다") {
            // given
            val trip = confirmedTrip()
            val idempotencyKey = UUID.randomUUID()
            // expense_item.name은 50자까지라 51자 품목명은 지출을 저장한 뒤 품목 저장에서 실패합니다.
            val failingCommand =
                byItem(
                    trip.adminId,
                    "1000.00",
                    listOf(CreateExpenseCommand.Item("가".repeat(51), BigDecimal("1000.00"), BigDecimal("0.00"), listOf(trip.adminId))),
                )

            // when
            val failure =
                shouldThrow<DataIntegrityViolationException> {
                    createExpenseUseCase.create(trip.adminMemberId, trip.tripId, idempotencyKey, failingCommand)
                }
            val expensesAfterFailure = countExpenses(idempotencyKey)
            val retried =
                createExpenseUseCase.create(
                    trip.adminMemberId,
                    trip.tripId,
                    idempotencyKey,
                    equal(trip.adminId, "1000.00", listOf(trip.adminId)),
                )

            // then
            assertSoftly {
                failure.message.orEmpty().contains("expense_item") shouldBe true
                expensesAfterFailure shouldBe 0
                countTripExpenses(trip.tripId) shouldBe 1
                countRows("expense_share_amount", retried.expenseId) shouldBe 1
            }
        }
    }

    /** 확정 상태를 직접 정하려고 여행 생성 API 대신 DB에 바로 넣습니다. 관리자, 로그인 참여자, 비로그인 참여자와 나간 회원을 둡니다. */
    private fun confirmedTrip(status: String = "CONFIRMED"): TripFixture {
        val key = UUID.randomUUID()
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, invite_code, " +
                        "idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', '2026-11-05', '2026-11-07', 'JP', 'JPY', 4, ?::trip_status, ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    status,
                    key.toString().replace("-", "").take(20),
                    key,
                ),
            )
        tripIds.add(tripId)
        val adminMemberId = createMember()
        val memberMemberId = createMember()
        val leftMemberId = createMember()
        return TripFixture(
            tripId = tripId,
            adminMemberId = adminMemberId,
            adminId = addTripMember(tripId, adminMemberId, "민지", "ADMIN", "MEMBER"),
            memberMemberId = memberMemberId,
            memberId = addTripMember(tripId, memberMemberId, "지훈", "MEMBER", "MEMBER"),
            guestId = addTripMember(tripId, null, "서연", "MEMBER", "GUEST"),
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
            splitType = SplitType.EQUAL,
            currency = ExpenseCurrency.KRW,
            payer = payer,
            originalAmount = originalAmount,
            discountAmount = "0.00",
            exchangeRate = "1.00",
            tripMemberIds = tripMemberIds,
            items = null,
        )

    private fun byItem(
        payer: Long,
        originalAmount: String,
        items: List<CreateExpenseCommand.Item>,
    ): CreateExpenseCommand =
        command(
            splitType = SplitType.ITEM,
            currency = ExpenseCurrency.KRW,
            payer = payer,
            originalAmount = originalAmount,
            discountAmount = "0.00",
            exchangeRate = "1.00",
            tripMemberIds = null,
            items = items,
        )

    private fun command(
        splitType: SplitType,
        currency: ExpenseCurrency,
        payer: Long,
        originalAmount: String,
        discountAmount: String,
        exchangeRate: String,
        tripMemberIds: List<Long>?,
        items: List<CreateExpenseCommand.Item>?,
    ): CreateExpenseCommand =
        CreateExpenseCommand(
            name = "식사",
            splitType = splitType,
            currency = currency,
            paidDate = LocalDate.parse("2026-11-05"),
            paymentMethod = PaymentMethod.CARD,
            payerTripMemberId = payer,
            originalAmount = BigDecimal(originalAmount),
            discountAmount = BigDecimal(discountAmount),
            exchangeRate = BigDecimal(exchangeRate),
            exchangeRateSource = RateSource.MANUAL,
            tripMemberIds = tripMemberIds,
            items = items,
        )

    private fun findExpense(expenseId: Long): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT split_type::text AS split_type, original_amount, discount_amount, amount, krw_amount, created_by_trip_member_id, " +
                "exchange_rate_provider FROM expense WHERE id = ?",
            expenseId,
        )

    private fun findShareAmounts(expenseId: Long): List<List<Long>> =
        jdbc.query(
            "SELECT trip_member_id, amount, krw_amount FROM expense_share_amount WHERE expense_id = ? ORDER BY trip_member_id",
            { rs, _ -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3)) },
            expenseId,
        )

    private fun countRows(
        table: String,
        expenseId: Long,
    ): Int = checkNotNull(jdbc.queryForObject("SELECT count(*) FROM $table WHERE expense_id = ?", Int::class.java, expenseId))

    private fun countExpenses(idempotencyKey: UUID): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM expense WHERE idempotency_key = ?", Int::class.java, idempotencyKey))

    private fun countTripExpenses(tripId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM expense WHERE trip_id = ?", Int::class.java, tripId))

    private data class TripFixture(
        val tripId: Long,
        val adminMemberId: Long,
        val adminId: Long,
        val memberMemberId: Long,
        val memberId: Long,
        val guestId: Long,
        val leftMemberId: Long,
        val leftId: Long,
    )
}
