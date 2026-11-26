package com.ject.module.expense.presentation.request

import com.ject.module.expense.presentation.ExpenseSplitType

/** 영수증 인식 요청의 JSON 파트입니다. [splitType]에 따라 품목을 돌려줄지 정합니다. */
data class RecognizeReceiptRequest(
    val splitType: ExpenseSplitType,
)
