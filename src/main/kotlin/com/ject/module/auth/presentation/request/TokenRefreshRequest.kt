package com.ject.module.auth.presentation.request

import jakarta.validation.constraints.NotEmpty

data class TokenRefreshRequest(
    @field:NotEmpty
    val refreshToken: String,
)
