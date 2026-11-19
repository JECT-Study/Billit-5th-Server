package com.ject.module.auth.domain

/**
 * 검증한 소셜 ID 토큰의 사용자입니다.
 *
 * [subject]는 제공자의 외부 사용자 ID(`sub`)입니다. 표시 이름 같은 다른 프로필 값은 읽지 않습니다.
 */
data class SocialIdentity(
    val subject: String,
)
