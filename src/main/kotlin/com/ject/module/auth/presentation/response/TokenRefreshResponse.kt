package com.ject.module.auth.presentation.response

data class TokenRefreshResponse(
    val accessToken: String,
    val refreshToken: String,
)
