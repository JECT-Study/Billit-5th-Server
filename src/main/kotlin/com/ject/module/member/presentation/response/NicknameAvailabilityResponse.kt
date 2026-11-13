package com.ject.module.member.presentation.response

data class NicknameAvailabilityResponse(
    val nickname: String,
    val isAvailable: Boolean,
)
