package com.ject.module.expense.presentation.response

import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.PaymentMethod
import com.ject.module.expense.domain.RateSource
import com.ject.module.expense.presentation.ExpenseSplitType
import java.time.LocalDate

/** 명세의 `ExpenseDetail`입니다. [settlementId], [exchangeRateProvider]와 [exchangeRateBaseDate]는 값이 없으면 `null`입니다. */
data class ExpenseDetailResponse(
    val expenseId: String,
    val name: String,
    val paidDate: LocalDate,
    val currency: ExpenseCurrency,
    val amount: String,
    val payerTripMemberId: String,
    val settlementId: String?,
    val isEditLocked: Boolean,
    val tripId: String,
    val splitType: ExpenseSplitType,
    val createdByTripMemberId: String,
    val paymentMethod: PaymentMethod,
    val originalAmount: String,
    val discountAmount: String,
    val exchangeRate: String,
    val exchangeRateSource: RateSource,
    val exchangeRateProvider: String?,
    val exchangeRateBaseDate: LocalDate?,
    val amountKrw: String,
    val items: List<Item>,
    val shares: List<Share>,
) {
    /** 명세의 `ExpenseItem`입니다. [name]은 이름 없는 품목이면 `null`입니다. */
    data class Item(
        val expenseItemId: String,
        val name: String?,
        val amount: String,
        val discountAmount: String,
        val sortOrder: Int,
        val tripMemberIds: List<String>,
    )

    /** 명세의 `Share`입니다. */
    data class Share(
        val tripMemberId: String,
        val amount: String,
        val amountKrw: String,
    )
}
