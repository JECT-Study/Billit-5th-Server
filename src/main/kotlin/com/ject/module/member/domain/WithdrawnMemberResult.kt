package com.ject.module.member.domain

import java.time.Instant

/** 탈퇴한 회원과 탈퇴 시각입니다. */
data class WithdrawnMemberResult(
    val memberId: Long,
    val withdrawnAt: Instant,
)
