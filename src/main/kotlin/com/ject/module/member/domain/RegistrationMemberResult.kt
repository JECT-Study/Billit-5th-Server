package com.ject.module.member.domain

/** 가입 절차에서 확인하는 회원의 상태와 가입 화면에 표시할 값입니다. */
data class RegistrationMemberResult(
    val status: MemberStatus,
    val socialDisplayName: String?,
)
