package com.ject.module.auth.usecase.port

import com.ject.module.auth.domain.AppleAuthorizationResult

/** 애플 로그인의 인가 코드 교환과 연결 해제를 담당합니다. 두 호출 모두 서버가 만든 client secret으로 애플 토큰 API를 호출합니다. */
interface AppleAuthorizationPort {
    /**
     * 모바일 SDK에서 받은 인가 코드를 애플 토큰 API에서 교환합니다. 인가 코드는 한 번만 교환할 수 있습니다.
     *
     * 애플이 코드를 거절하면(만료, 재사용, `invalid_grant` 등) `AUTH_SOCIAL_AUTHENTICATION_FAILED`, 애플 장애·타임아웃이나 해석할 수 없는 응답이면 `AUTH_SOCIAL_PROVIDER_UNAVAILABLE`로 실패합니다.
     */
    fun exchangeAuthorizationCode(authorizationCode: String): AppleAuthorizationResult

    /**
     * 애플 Refresh Token을 폐기해 앱과 애플 계정의 연결을 해제합니다. 이미 폐기된 토큰도 애플은 성공으로 응답합니다.
     *
     * 애플이 거절하거나 장애·타임아웃이면 `AUTH_SOCIAL_PROVIDER_UNAVAILABLE`로 실패합니다.
     */
    fun revokeRefreshToken(refreshToken: String)
}
