package com.ject.module.member.presentation.response

data class RegistrationCompletedResponse(
    val accessToken: String,
    val refreshToken: String,
)
