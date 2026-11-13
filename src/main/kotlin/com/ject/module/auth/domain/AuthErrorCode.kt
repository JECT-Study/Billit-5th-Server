package com.ject.module.auth.domain

import com.ject.global.error.ErrorCode

enum class AuthErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    SOCIAL_AUTHENTICATION_FAILED(401, "소셜 인증에 실패했습니다."),
    SOCIAL_PROVIDER_UNAVAILABLE(503, "소셜 로그인 제공자에 연결할 수 없습니다."),
    ;

    override val code: String = "AUTH_$name"
}
