package com.ject.module.expense.usecase.port

import com.ject.module.expense.domain.ReceiptRecognitionResult

/** 영수증 이미지에서 지출 입력값을 읽습니다. 어떤 엔진으로 읽는지는 구현이 정하며 이미지를 저장하지 않습니다. */
interface ReceiptRecognitionPort {
    /**
     * [image]는 [mediaType] 형식의 원본 바이트입니다. 일부만 읽어도 읽은 값만 담아 반환합니다.
     *
     * 영수증이 아닌 이미지이면 400 `EXPENSE_NOT_RECEIPT`, 엔진을 쓸 수 없거나 장애, 타임아웃이나 해석할 수 없는 응답이면 503 `EXPENSE_RECOGNITION_UNAVAILABLE`로 실패합니다.
     */
    fun recognize(
        image: ByteArray,
        mediaType: String,
    ): ReceiptRecognitionResult
}
