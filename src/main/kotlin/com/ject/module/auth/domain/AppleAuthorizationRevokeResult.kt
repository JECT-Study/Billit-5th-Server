package com.ject.module.auth.domain

/** 애플 연결 해제 결과입니다. */
enum class AppleAuthorizationRevokeResult {
    /** 저장된 토큰으로 연결을 해제했습니다. */
    REVOKED,

    /** 저장된 토큰이 없어 애플을 호출하지 않았습니다. */
    NOT_FOUND,

    /** 저장된 토큰을 복호화하지 못해 애플을 호출하지 않았습니다. 저장된 행은 남아 있습니다. */
    UNDECRYPTABLE,
}
