package com.ject.module.member.domain

/** 소셜 계정으로 찾은 회원의 식별자, 상태와 저장된 소셜 표시 이름입니다. */
data class SocialMemberResult(
    val memberId: Long,
    val status: MemberStatus,
    val socialDisplayName: String?,
)
