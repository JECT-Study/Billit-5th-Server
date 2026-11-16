package com.ject.external.apple

enum class AppleTokenFailureReason {
    /** 개인키가 없어 client secret을 만들 수 없습니다. 애플을 호출하지 않습니다. */
    CLIENT_SECRET_UNAVAILABLE,

    /** 애플이 4xx로 요청을 거절했습니다. [AppleTokenException.statusCode]와 [AppleTokenException.error]를 함께 줍니다. */
    REJECTED,

    /** 연결 실패, 타임아웃이나 5xx 응답입니다. */
    UNAVAILABLE,

    /** 성공 응답이지만 본문이 없거나 ID 토큰의 `sub`를 읽을 수 없습니다. */
    INVALID_RESPONSE,
}
