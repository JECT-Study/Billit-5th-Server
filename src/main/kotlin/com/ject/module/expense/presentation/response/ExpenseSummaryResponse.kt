package com.ject.module.expense.presentation.response

import com.ject.module.expense.domain.ExpenseCurrency
import java.time.LocalDate

/** 명세의 `ExpenseSummary`입니다. [settlementId]는 연결된 정산이 없으면 `null`입니다. */
data class ExpenseSummaryResponse(
    val expenseId: String,
    val name: String,
    val paidDate: LocalDate,
    val currency: ExpenseCurrency,
    val amount: String,
    val payerTripMemberId: String,
    val settlementId: String?,
    val isEditLocked: Boolean,
)
