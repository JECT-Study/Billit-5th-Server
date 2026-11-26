package com.ject.external.gemini

/** Gemini 호출 실패입니다. 요청 이미지와 응답 본문은 담지 않습니다. */
class GeminiException(
    val reason: GeminiFailureReason,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException("Gemini API 호출에 실패했습니다: $reason", cause)

enum class GeminiFailureReason {
    /** 연결하지 못했습니다. */
    CONNECTION,

    /** 응답 타임아웃입니다. */
    TIMEOUT,

    /** 4xx나 5xx로 응답했습니다. [GeminiException.statusCode]를 함께 줍니다. */
    HTTP_ERROR,

    /** 요청이 차단됐거나 생성을 끝까지 마치지 못했거나 응답에 텍스트가 없습니다. */
    INVALID_RESPONSE,
}
