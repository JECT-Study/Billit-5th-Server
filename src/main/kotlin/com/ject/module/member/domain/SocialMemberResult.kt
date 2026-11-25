package com.ject.module.member.domain

/** 소셜 계정으로 찾은 회원의 식별자와 상태입니다. */
data class SocialMemberResult(
    val memberId: Long,
    val status: MemberStatus,
)
