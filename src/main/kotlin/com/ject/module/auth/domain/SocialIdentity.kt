package com.ject.module.auth.domain

/**
 * 검증한 소셜 ID 토큰의 사용자입니다.
 *
 * [subject]는 제공자의 외부 사용자 ID(`sub`), [displayName]은 가입 화면의 닉네임 초기값으로 쓸 제공자 표시 이름입니다. 제공자가 주지 않으면 `null`입니다.
 */
data class SocialIdentity(
    val subject: String,
    val displayName: String?,
)
