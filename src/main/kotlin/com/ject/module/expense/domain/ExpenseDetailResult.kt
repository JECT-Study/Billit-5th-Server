package com.ject.module.expense.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 저장된 지출의 상세입니다. 금액은 결제 통화 단위, 원화 금액은 원 단위 값입니다.
 *
 * [items]는 표시 순서대로, [Item.tripMemberIds]와 [shares]는 여행 참여자 ID 순서대로 담습니다. 전체 1/N 지출이면 [items]는 비어 있습니다.
 * [exchangeRateBaseDate]는 기준일을 저장하는 칼럼이 아직 없어 항상 `null`입니다.
 */
data class ExpenseDetailResult(
    val expenseId: Long,
    val name: String,
    val paidDate: LocalDate,
    val currency: ExpenseCurrency,
    val amount: BigDecimal,
    val payerTripMemberId: Long,
    val settlementId: Long?,
    val isEditLocked: Boolean,
    val tripId: Long,
    val splitType: SplitType,
    val createdByTripMemberId: Long,
    val paymentMethod: PaymentMethod,
    val originalAmount: BigDecimal,
    val discountAmount: BigDecimal,
    val exchangeRate: BigDecimal,
    val exchangeRateSource: RateSource,
    val exchangeRateProvider: String?,
    val exchangeRateBaseDate: LocalDate?,
    val amountKrw: BigDecimal,
    val items: List<Item>,
    val shares: List<Share>,
) {
    data class Item(
        val expenseItemId: Long,
        val name: String?,
        val amount: BigDecimal,
        val discountAmount: BigDecimal,
        val sortOrder: Int,
        val tripMemberIds: List<Long>,
    )

    data class Share(
        val tripMemberId: Long,
        val amount: BigDecimal,
        val amountKrw: BigDecimal,
    )
}
