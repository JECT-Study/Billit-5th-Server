package com.ject.module.expense.infra.client

import com.ject.global.error.BusinessException
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ReceiptRecognitionResult
import com.ject.module.expense.usecase.port.ReceiptRecognitionPort

/** 인식 엔진을 끄거나 API 키가 없을 때 쓰는 구현입니다. 외부를 호출하지 않고 항상 503 `EXPENSE_RECOGNITION_UNAVAILABLE`로 실패합니다. */
class UnavailableReceiptRecognitionAdapter : ReceiptRecognitionPort {
    override fun recognize(
        image: ByteArray,
        mediaType: String,
    ): ReceiptRecognitionResult = throw BusinessException(ExpenseErrorCode.RECOGNITION_UNAVAILABLE)
}
