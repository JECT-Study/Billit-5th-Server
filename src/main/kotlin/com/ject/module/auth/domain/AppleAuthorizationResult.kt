package com.ject.module.auth.domain

/**
 * 애플 인가 코드를 교환한 결과입니다.
 *
 * [subject]는 교환 응답 ID 토큰의 `sub`, [refreshToken]은 연결 해제에 쓸 애플 Refresh Token입니다. 응답에 없으면 각각 `null`입니다.
 */
data class AppleAuthorizationResult(
    val subject: String?,
    val refreshToken: String?,
)
