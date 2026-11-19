package com.ject.module.member.domain

/** 가입을 마친 회원의 프로필 화면 정보입니다. 프로필 이미지는 아직 저장하지 않습니다. */
data class MemberProfileResult(
    val memberId: Long,
    val nickname: String,
    val socialProvider: AuthProvider,
)
