package com.ject.module.auth.domain

/** 회원 토큰과 가입 전용 토큰에 담는 클레임 이름입니다. 토큰 발급과 요청 인증이 같은 이름을 사용합니다. */
object AuthTokenClaims {
    const val TOKEN_TYPE = "token_type"
    const val SESSION_ID = "sid"
}
