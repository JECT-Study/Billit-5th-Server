package com.ject.module.expense.infra.persistence

import com.ject.global.persistence.violatedUniqueConstraint
import com.ject.module.expense.domain.Expense
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.ExpenseIdempotencyKeyDuplicatedException
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.domain.PaymentMethod
import com.ject.module.expense.domain.RateSource
import com.ject.module.expense.domain.SplitType
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class ExpenseRepositoryAdapterTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var expenseRepository: ExpenseRepository

    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM expense WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
        }

        test("같은 고유키로 지출을 다시 저장하면 ExpenseIdempotencyKeyDuplicatedException을 던지고 처음 지출만 남는다") {
            // given
            val tripId = createTrip()
            val tripMemberId = addGuest(tripId)
            val idempotencyKey = UUID.randomUUID()
            val firstId = expenseRepository.saveExpense(expense(tripId, tripMemberId, idempotencyKey, name = "식사"))

            // when
            val failure =
                shouldThrow<ExpenseIdempotencyKeyDuplicatedException> {
                    expenseRepository.saveExpense(expense(tripId, tripMemberId, idempotencyKey, name = "택시"))
                }

            // then
            assertSoftly {
                (failure.cause as DataIntegrityViolationException).violatedUniqueConstraint() shouldBe "uq_expense_idempotency_key"
                jdbc.queryForList("SELECT id, name FROM expense WHERE idempotency_key = ?", idempotencyKey) shouldBe
                    listOf(mapOf("id" to firstId, "name" to "식사"))
            }
        }

        test("고유키 중복이 아닌 무결성 오류로 지출 저장이 실패하면 오류를 그대로 던진다") {
            // given
            val tripId = createTrip()
            val tripMemberId = addGuest(tripId)
            val idempotencyKey = UUID.randomUUID()
            // expense.name은 50자까지라 51자 지출명은 길이 제한에 걸립니다.
            val expense = expense(tripId, tripMemberId, idempotencyKey, name = "가".repeat(51))

            // when
            val failure = shouldThrow<DataIntegrityViolationException> { expenseRepository.saveExpense(expense) }

            // then
            assertSoftly {
                failure.violatedUniqueConstraint() shouldBe null
                countExpenses(idempotencyKey) shouldBe 0
            }
        }
    }

    /** 테스트가 끝나면 지웁니다. 지출의 외래 키를 채우려고 여행 생성 API 대신 DB에 바로 넣습니다. */
    private fun createTrip(): Long {
        val key = UUID.randomUUID()
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, invite_code, " +
                        "idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', '2026-11-05', '2026-11-07', 'JP', 'JPY', 3, 'CONFIRMED', ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    key.toString().replace("-", "").take(20),
                    key,
                ),
            )
        tripIds.add(id)
        return id
    }

    private fun addGuest(tripId: Long): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, trip_nickname, role, status, created_at, updated_at) " +
                    "VALUES (?, '서연', 'MEMBER', 'GUEST', now(), now()) RETURNING id",
                Long::class.java,
                tripId,
            ),
        )

    private fun expense(
        tripId: Long,
        tripMemberId: Long,
        idempotencyKey: UUID,
        name: String,
    ): Expense =
        Expense(
            tripId = tripId,
            idempotencyKey = idempotencyKey,
            name = name,
            splitType = SplitType.EQUAL,
            originalAmount = 12000,
            discountAmount = 0,
            amount = 12000,
            currency = ExpenseCurrency.KRW,
            paidDate = LocalDate.parse("2026-11-05"),
            payerTripMemberId = tripMemberId,
            createdByTripMemberId = tripMemberId,
            paymentMethod = PaymentMethod.CARD,
            exchangeRate = BigDecimal.ONE,
            exchangeRateSource = RateSource.MANUAL,
            exchangeRateProvider = null,
            krwAmount = 12000,
            editLockOwnerTripMemberId = null,
            editLockExpiresAt = null,
            editLockToken = null,
            settlementId = null,
        )

    private fun countExpenses(idempotencyKey: UUID): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM expense WHERE idempotency_key = ?", Int::class.java, idempotencyKey))
}
