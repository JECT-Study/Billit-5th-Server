package com.ject.module.auth.presentation.request

import com.ject.module.member.domain.AuthProvider
import jakarta.validation.constraints.NotBlank

/** 구글과 카카오의 ID 토큰 로그인 요청입니다. Apple 요청 형식은 Apple 로그인을 구현할 때 추가합니다. */
data class SocialLoginRequest(
    val provider: AuthProvider,
    @field:NotBlank
    val idToken: String,
)
