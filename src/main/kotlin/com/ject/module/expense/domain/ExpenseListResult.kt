package com.ject.module.expense.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 지출 목록의 한 페이지입니다.
 *
 * [totalElements]는 조건에 맞는 전체 지출 수이고, [hasNext]는 다음 페이지가 있는지입니다.
 */
data class ExpenseListResult(
    val items: List<Item>,
    val totalElements: Long,
    val hasNext: Boolean,
) {
    /** [amount]는 결제 통화 단위의 최종 결제액이고, [isEditLocked]는 조회 시점에 만료되지 않은 수정 잠금이 있는지입니다. */
    data class Item(
        val expenseId: Long,
        val name: String,
        val paidDate: LocalDate,
        val currency: ExpenseCurrency,
        val amount: BigDecimal,
        val payerTripMemberId: Long,
        val settlementId: Long?,
        val isEditLocked: Boolean,
    )
}
