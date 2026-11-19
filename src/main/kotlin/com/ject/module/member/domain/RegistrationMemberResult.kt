package com.ject.module.member.domain

/** 가입 절차에서 확인하는 회원의 상태입니다. */
data class RegistrationMemberResult(
    val status: MemberStatus,
)
