package com.ject.module.expense.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 지출 생성 입력입니다. 금액과 환율은 요청에 받은 값 그대로이며, 통화의 최소 단위로 바꾸는 일은 [com.ject.module.expense.implement.ExpenseCalculator]가 합니다.
 *
 * 전체 1/N([SplitType.EQUAL])이면 [tripMemberIds]만, 품목별([SplitType.ITEM])이면 [items]만 값이 있어야 합니다.
 */
data class CreateExpenseCommand(
    val name: String,
    val splitType: SplitType,
    val currency: ExpenseCurrency,
    val paidDate: LocalDate,
    val paymentMethod: PaymentMethod,
    val payerTripMemberId: Long,
    val originalAmount: BigDecimal,
    val discountAmount: BigDecimal,
    val exchangeRate: BigDecimal,
    val exchangeRateSource: RateSource,
    val tripMemberIds: List<Long>?,
    val items: List<Item>?,
) {
    data class Item(
        val name: String?,
        val amount: BigDecimal,
        val discountAmount: BigDecimal,
        val tripMemberIds: List<Long>,
    )
}
