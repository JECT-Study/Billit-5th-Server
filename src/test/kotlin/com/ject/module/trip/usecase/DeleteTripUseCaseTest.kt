package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.PaymentMethod
import com.ject.module.expense.domain.RateSource
import com.ject.module.expense.domain.SplitType
import com.ject.module.expense.usecase.CreateExpenseUseCase
import com.ject.module.trip.domain.TripErrorCode
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 지출이 있는 여행의 삭제를 막는지, 삭제와 지출 저장이 동시에 들어와도 지출이 남은 여행이 삭제되지 않는지 실제 PostgreSQL에서 확인합니다. */
class DeleteTripUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var deleteTripUseCase: DeleteTripUseCase

    @Autowired
    private lateinit var createExpenseUseCase: CreateExpenseUseCase

    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM expense_share_amount WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM expense WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id -> jdbc.update("DELETE FROM member WHERE id = ?", id) }
            memberIds.clear()
        }

        test("지출이 없는 여행을 관리자가 삭제하면 삭제 시각을 저장한다") {
            // given
            val trip = confirmedTrip()

            // when
            deleteTripUseCase.delete(trip.adminMemberId, trip.tripId)

            // then
            findDeletedAt(trip.tripId) shouldNotBe null
        }

        test("삭제되지 않은 지출이 있는 여행을 삭제하면 409로 거절하고 여행을 남긴다") {
            // given
            val trip = confirmedTrip()
            createExpenseUseCase.create(trip.adminMemberId, trip.tripId, UUID.randomUUID(), expense(trip.adminId))

            // when
            val exception = shouldThrow<BusinessException> { deleteTripUseCase.delete(trip.adminMemberId, trip.tripId) }

            // then
            assertSoftly {
                exception.errorCode shouldBe TripErrorCode.RECORDS_EXIST
                findDeletedAt(trip.tripId) shouldBe null
            }
        }

        test("지출이 모두 삭제된 여행은 삭제할 수 있다") {
            // given
            val trip = confirmedTrip()
            val expenseId =
                createExpenseUseCase.create(trip.adminMemberId, trip.tripId, UUID.randomUUID(), expense(trip.adminId)).expenseId
            jdbc.update("UPDATE expense SET deleted_at = now() WHERE id = ?", expenseId)

            // when
            deleteTripUseCase.delete(trip.adminMemberId, trip.tripId)

            // then
            findDeletedAt(trip.tripId) shouldNotBe null
        }

        test("여행 삭제와 지출 저장이 동시에 들어오면 지출이 남은 여행은 삭제되지 않는다") {
            repeat(REPEAT_COUNT) {
                // given
                val trip = confirmedTrip()
                val barrier = CyclicBarrier(2)
                val executor = Executors.newFixedThreadPool(2)

                // when
                val save =
                    executor.submit<Throwable?> {
                        barrier.await()
                        runCatching {
                            createExpenseUseCase.create(
                                trip.adminMemberId,
                                trip.tripId,
                                UUID.randomUUID(),
                                expense(trip.adminId),
                            )
                        }.exceptionOrNull()
                    }
                val delete =
                    executor.submit<Throwable?> {
                        barrier.await()
                        runCatching { deleteTripUseCase.delete(trip.adminMemberId, trip.tripId) }.exceptionOrNull()
                    }
                val saveFailure = save.get(30, TimeUnit.SECONDS)
                val deleteFailure = delete.get(30, TimeUnit.SECONDS)
                executor.shutdown()

                // then
                val deleted = findDeletedAt(trip.tripId) != null
                val expenses = countActiveExpenses(trip.tripId)
                assertSoftly {
                    (deleted && expenses > 0) shouldBe false
                    if (deleted) {
                        (saveFailure as BusinessException).errorCode shouldBe CommonErrorCode.NOT_FOUND
                    } else {
                        (deleteFailure as BusinessException).errorCode shouldBe TripErrorCode.RECORDS_EXIST
                    }
                }
            }
        }
    }

    private fun confirmedTrip(): TripFixture {
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
        val adminMemberId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(adminMemberId)
        val adminId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, '민지', 'ADMIN', 'MEMBER', now(), now()) RETURNING id",
                    Long::class.java,
                    tripId,
                    adminMemberId,
                ),
            )
        return TripFixture(tripId, adminMemberId, adminId)
    }

    private fun expense(adminId: Long): CreateExpenseCommand =
        CreateExpenseCommand(
            name = "식사",
            splitType = SplitType.EQUAL,
            currency = ExpenseCurrency.KRW,
            paidDate = LocalDate.parse("2026-11-05"),
            paymentMethod = PaymentMethod.CARD,
            payerTripMemberId = adminId,
            originalAmount = BigDecimal("12000.00"),
            discountAmount = BigDecimal("0.00"),
            exchangeRate = BigDecimal("1.00"),
            exchangeRateSource = RateSource.MANUAL,
            tripMemberIds = listOf(adminId),
            items = null,
        )

    private fun findDeletedAt(tripId: Long): Any? = jdbc.queryForObject("SELECT deleted_at FROM trip WHERE id = ?", Any::class.java, tripId)

    private fun countActiveExpenses(tripId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM expense WHERE trip_id = ? AND deleted_at IS NULL", Int::class.java, tripId))

    private data class TripFixture(
        val tripId: Long,
        val adminMemberId: Long,
        val adminId: Long,
    )

    companion object {
        /** 두 요청의 도착 순서가 바뀌는 경우를 모두 보도록 여러 번 반복합니다. */
        private const val REPEAT_COUNT = 10
    }
}
