package com.ject.module.auth.implement

import com.ject.module.auth.domain.AuthTokenClaims
import com.ject.module.auth.domain.RefreshTokenSession
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RefreshTokenReader(
    @param:Qualifier("refreshJwtDecoder") private val refreshJwtDecoder: JwtDecoder,
) {
    /** 서명, 만료 시각과 토큰 종류가 유효한 Refresh Token의 세션과 회원을 읽습니다. 유효하지 않으면 `null`을 반환합니다. */
    fun read(refreshToken: String): RefreshTokenSession? {
        val jwt =
            try {
                refreshJwtDecoder.decode(refreshToken)
            } catch (_: JwtException) {
                return null
            }
        val sessionId = jwt.getClaimAsString(AuthTokenClaims.SESSION_ID)?.let(::parseUuid) ?: return null
        val memberId = jwt.subject?.toLongOrNull() ?: return null
        return RefreshTokenSession(sessionId = sessionId, memberId = memberId)
    }

    private fun parseUuid(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            null
        }
}
