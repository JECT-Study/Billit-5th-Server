package com.ject.module.expense.implement

import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.Expense
import com.ject.module.expense.domain.ExpenseCalculation
import com.ject.module.expense.domain.ExpenseIdempotencyKeyDuplicatedException
import com.ject.module.expense.domain.ExpenseItem
import com.ject.module.expense.domain.ExpenseItemMember
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.domain.ExpenseShareAmount
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class ExpenseCreator(
    private val expenseRepository: ExpenseRepository,
) {
    /**
     * 지출과 고유키, 품목, 품목별 부담자와 참여자별 최종 부담액을 한 트랜잭션에 저장하고 지출 ID를 반환합니다. 하나라도 실패하면 모두 저장하지 않습니다.
     *
     * - 품목의 표시 순서는 요청한 순서대로 0부터 매깁니다. 전체 1/N 지출이면 품목과 품목별 부담자는 저장하지 않습니다.
     * - 환율 제공 기관은 환율 조회 기능 전이라 저장하지 않습니다.
     * - 같은 고유키의 지출이 이미 있으면 [ExpenseIdempotencyKeyDuplicatedException]으로 롤백합니다. 기존 지출 조회는 호출하는 쪽이 이 트랜잭션 밖에서 합니다.
     */
    @Transactional
    fun create(
        tripId: Long,
        createdByTripMemberId: Long,
        idempotencyKey: UUID,
        command: CreateExpenseCommand,
        calculation: ExpenseCalculation,
    ): Long {
        val expenseId =
            expenseRepository.saveExpense(
                Expense(
                    tripId = tripId,
                    idempotencyKey = idempotencyKey,
                    name = command.name,
                    splitType = command.splitType,
                    originalAmount = calculation.originalAmount,
                    discountAmount = calculation.discountAmount,
                    amount = calculation.amount,
                    currency = command.currency,
                    paidDate = command.paidDate,
                    payerTripMemberId = command.payerTripMemberId,
                    createdByTripMemberId = createdByTripMemberId,
                    paymentMethod = command.paymentMethod,
                    exchangeRate = command.exchangeRate,
                    exchangeRateSource = command.exchangeRateSource,
                    exchangeRateProvider = null,
                    krwAmount = calculation.krwAmount,
                    editLockOwnerTripMemberId = null,
                    editLockExpiresAt = null,
                    editLockToken = null,
                    settlementId = null,
                ),
            )

        command.items.orEmpty().zip(calculation.items).forEachIndexed { sortOrder, (input, item) ->
            val itemId =
                expenseRepository.saveItem(
                    ExpenseItem(
                        expenseId = expenseId,
                        name = input.name,
                        amount = item.amount,
                        discountAmount = item.discountAmount,
                        sortOrder = sortOrder,
                    ),
                )
            expenseRepository.saveItemMembers(
                input.tripMemberIds.map { ExpenseItemMember(expenseItemId = itemId, tripMemberId = it, expenseId = expenseId) },
            )
        }

        expenseRepository.saveShareAmounts(
            calculation.shares.map {
                ExpenseShareAmount(
                    expenseId = expenseId,
                    tripMemberId = it.tripMemberId,
                    tripId = tripId,
                    amount = it.amount,
                    krwAmount = it.krwAmount,
                )
            },
        )
        return expenseId
    }
}
