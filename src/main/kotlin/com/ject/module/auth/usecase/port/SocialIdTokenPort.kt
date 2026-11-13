package com.ject.module.auth.usecase.port

import com.ject.module.member.domain.AuthProvider

interface SocialIdTokenPort {
    /**
     * 모바일 SDK에서 받은 ID 토큰의 서명(제공자 JWKS, RS256), `iss`, `aud`, `exp`를 검증하고 외부 사용자 ID(`sub`)를 반환합니다.
     *
     * 토큰이 유효하지 않으면 `AUTH_SOCIAL_AUTHENTICATION_FAILED`, 제공자 JWKS를 가져오지 못하면 `AUTH_SOCIAL_PROVIDER_UNAVAILABLE`로 실패합니다.
     * ID 토큰으로 로그인하는 [AuthProvider.GOOGLE], [AuthProvider.KAKAO]만 지원합니다.
     */
    fun verify(
        provider: AuthProvider,
        idToken: String,
    ): String
}
