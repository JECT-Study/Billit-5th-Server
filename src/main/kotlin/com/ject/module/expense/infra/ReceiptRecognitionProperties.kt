package com.ject.module.expense.infra

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.expense.receipt-recognition")
class ReceiptRecognitionProperties(
    /** 영수증 인식 엔진입니다. */
    val provider: Provider,
) {
    enum class Provider {
        /** Gemini API로 인식합니다. `app.gemini.api-key`가 비어 있으면 [NONE]과 같습니다. */
        GEMINI,

        /** 인식하지 않고 503으로 응답합니다. */
        NONE,
    }
}
