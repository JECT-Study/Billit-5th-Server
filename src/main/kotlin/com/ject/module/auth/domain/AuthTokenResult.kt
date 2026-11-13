package com.ject.module.auth.domain

data class AuthTokenResult(
    val accessToken: String,
    val refreshToken: String,
)
