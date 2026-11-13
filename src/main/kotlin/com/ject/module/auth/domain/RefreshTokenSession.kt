package com.ject.module.auth.domain

import java.util.UUID

/** 서명과 만료 시각을 확인한 Refresh Token이 가리키는 세션과 회원입니다. 세션의 유효 여부는 확인하지 않은 값입니다. */
data class RefreshTokenSession(
    val sessionId: UUID,
    val memberId: Long,
)
