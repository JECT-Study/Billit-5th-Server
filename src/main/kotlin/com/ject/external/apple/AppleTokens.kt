package com.ject.external.apple

/** 인가 코드 교환 결과입니다. [refreshToken]은 애플이 주지 않거나 빈 값이면 `null`입니다. */
data class AppleTokens(
    val subject: String,
    val refreshToken: String?,
)
