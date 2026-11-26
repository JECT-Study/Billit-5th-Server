package com.ject.module.expense.implement

import com.ject.module.expense.domain.ExpenseDetailResult
import com.ject.module.expense.domain.ExpenseRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock

@Component
class ExpenseDetailReader(
    private val expenseRepository: ExpenseRepository,
    private val clock: Clock,
) {
    /**
     * 지출, 품목, 품목별 부담자와 최종 부담액을 같은 시점의 스냅샷으로 읽도록 `REPEATABLE READ` 읽기 전용 트랜잭션에서 조회합니다.
     *
     * 삭제된 지출도 조회하므로 삭제 여부는 호출하는 쪽이 확인합니다. 수정 잠금은 조회 시점에 만료되지 않았을 때만 잠긴 것으로 봅니다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(expenseId: Long): ExpenseDetailResult {
        val expense = checkNotNull(expenseRepository.findExpense(expenseId)) { "지출이 없습니다. expenseId=$expenseId" }
        val itemMembers = expenseRepository.findItemMembers(expenseId).groupBy({ it.expenseItemId }, { it.tripMemberId })
        val fractionDigits = expense.currency.fractionDigits
        val editLockExpiresAt = expense.editLockExpiresAt

        return ExpenseDetailResult(
            expenseId = expenseId,
            name = expense.name,
            paidDate = expense.paidDate,
            currency = expense.currency,
            amount = BigDecimal.valueOf(expense.amount, fractionDigits),
            payerTripMemberId = expense.payerTripMemberId,
            settlementId = expense.settlementId,
            isEditLocked = editLockExpiresAt != null && editLockExpiresAt.isAfter(clock.instant()),
            tripId = expense.tripId,
            splitType = expense.splitType,
            createdByTripMemberId = expense.createdByTripMemberId,
            paymentMethod = expense.paymentMethod,
            originalAmount = BigDecimal.valueOf(expense.originalAmount, fractionDigits),
            discountAmount = BigDecimal.valueOf(expense.discountAmount, fractionDigits),
            exchangeRate = expense.exchangeRate,
            exchangeRateSource = expense.exchangeRateSource,
            exchangeRateProvider = expense.exchangeRateProvider,
            exchangeRateBaseDate = null,
            amountKrw = BigDecimal.valueOf(expense.krwAmount),
            items =
                expenseRepository.findItems(expenseId).map {
                    val itemId = checkNotNull(it.id)
                    ExpenseDetailResult.Item(
                        expenseItemId = itemId,
                        name = it.name,
                        amount = BigDecimal.valueOf(it.amount, fractionDigits),
                        discountAmount = BigDecimal.valueOf(it.discountAmount, fractionDigits),
                        sortOrder = it.sortOrder,
                        tripMemberIds = itemMembers[itemId].orEmpty().sorted(),
                    )
                },
            shares =
                expenseRepository.findShareAmounts(expenseId).map {
                    ExpenseDetailResult.Share(
                        tripMemberId = it.tripMemberId,
                        amount = BigDecimal.valueOf(it.amount, fractionDigits),
                        amountKrw = BigDecimal.valueOf(it.krwAmount),
                    )
                },
        )
    }
}
