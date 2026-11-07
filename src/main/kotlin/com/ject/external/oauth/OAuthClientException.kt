package com.ject.external.oauth

class OAuthClientException(
    val reason: Reason,
    val statusCode: Int? = null,
) : RuntimeException("소셜 인증 제공자 연동에 실패했습니다: $reason") {
    enum class Reason {
        INVALID_AUTHORIZATION,
        CONFIGURATION,
        UNAVAILABLE,
        INVALID_RESPONSE,
    }
}
