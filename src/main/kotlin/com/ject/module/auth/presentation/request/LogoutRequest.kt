package com.ject.module.auth.presentation.request

import jakarta.validation.constraints.NotEmpty

/** Access Token이 만료됐을 때만 보내는 본문입니다. */
data class LogoutRequest(
    @field:NotEmpty
    val refreshToken: String,
)
