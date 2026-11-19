package com.ject.module.member.presentation.response

import com.ject.module.member.domain.AuthProvider

data class MemberProfileResponse(
    val memberId: String,
    val nickname: String,
    val profileImageUrl: String?,
    val socialProvider: AuthProvider,
)
