package com.ject.external.socialidtoken

enum class IdTokenFailureReason {
    /** 서명, 형식이나 클레임(`iss`, `aud`, `exp`, `sub`)이 유효하지 않습니다. */
    INVALID_TOKEN,

    /** 제공자 JWKS를 가져오지 못해 서명 키를 확인할 수 없습니다. */
    KEY_SOURCE_UNAVAILABLE,
}
