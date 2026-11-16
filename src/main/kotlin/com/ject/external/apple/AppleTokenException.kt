package com.ject.external.apple

class AppleTokenException(
    val reason: AppleTokenFailureReason,
    val statusCode: Int? = null,
    /** 애플 오류 응답의 `error` 값입니다. 읽을 수 없으면 `null`입니다. */
    val error: String? = null,
    cause: Throwable? = null,
) : RuntimeException("애플 토큰 API 호출에 실패했습니다: $reason", cause)
