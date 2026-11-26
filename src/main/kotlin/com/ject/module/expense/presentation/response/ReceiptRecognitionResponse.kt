package com.ject.module.expense.presentation.response

import com.ject.module.expense.domain.ExpenseCurrency
import java.time.LocalDate

/** 명세의 `RecognitionResult`입니다. 읽지 못한 값은 `null`, 읽은 품목이 없으면 [items]는 빈 목록입니다. */
data class ReceiptRecognitionResponse(
    val name: String?,
    val paidDate: LocalDate?,
    val currency: ExpenseCurrency?,
    val originalAmount: String?,
    val discountAmount: String?,
    val items: List<Item>,
) {
    /** 명세의 `RecognizedItem`입니다. */
    data class Item(
        val name: String?,
        val amount: String?,
        val discountAmount: String?,
    )
}
