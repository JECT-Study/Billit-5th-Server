package com.ject.module.auth.domain

sealed interface SocialLoginResult {
    /** 가입을 마친 회원입니다. 새 세션의 회원용 토큰을 발급했습니다. */
    data class Authenticated(
        val tokens: AuthTokenResult,
    ) : SocialLoginResult

    /** 처음 로그인했거나 가입을 마치지 않은 회원입니다. 세션 없이 가입 전용 토큰만 발급했습니다. */
    data class RegistrationRequired(
        val registrationToken: String,
    ) : SocialLoginResult
}
