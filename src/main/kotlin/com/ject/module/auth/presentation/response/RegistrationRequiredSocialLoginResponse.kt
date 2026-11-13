package com.ject.module.auth.presentation.response

data class RegistrationRequiredSocialLoginResponse(
    override val status: SocialLoginStatus,
    val registrationToken: String,
) : SocialLoginResponse
