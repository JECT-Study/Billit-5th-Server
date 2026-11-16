package com.ject.module.auth.presentation.response

data class AuthenticatedSocialLoginResponse(
    override val status: SocialLoginStatus,
    val accessToken: String,
    val refreshToken: String,
) : SocialLoginResponse
