package com.ject.module.auth.presentation.response

/** 소셜 로그인 결과에 따라 [AuthenticatedSocialLoginResponse]나 [RegistrationRequiredSocialLoginResponse]로 응답합니다. */
sealed interface SocialLoginResponse {
    val status: SocialLoginStatus
}
