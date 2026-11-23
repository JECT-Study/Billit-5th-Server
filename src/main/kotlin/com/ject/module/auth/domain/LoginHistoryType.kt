package com.ject.module.auth.domain

/** 로그인 기록을 남긴 인증 행동입니다. */
enum class LoginHistoryType {
    /** 소셜 로그인이나 가입 완료로 새 로그인 세션을 발급했습니다. */
    LOGIN,

    /** 같은 로그인 세션의 토큰을 갱신했습니다. */
    TOKEN_REFRESH,
}
