package com.ject.external.socialidtoken

class IdTokenVerificationException(
    val reason: IdTokenFailureReason,
    cause: Throwable? = null,
) : RuntimeException("ID 토큰 검증에 실패했습니다: $reason", cause)
