package com.ject.module.auth.implement

import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat

/** Refresh Token 원문 대신 저장할 SHA-256 해시를 계산하고, 저장된 해시와 `MessageDigest.isEqual`로 비교합니다. */
@Component
class RefreshTokenHasher {
    fun hash(refreshToken: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray()))

    fun matches(
        refreshTokenHash: String,
        storedHash: String,
    ): Boolean = MessageDigest.isEqual(refreshTokenHash.toByteArray(), storedHash.toByteArray())
}
