package com.ject.module.auth.infra

import com.ject.module.auth.domain.AuthSession
import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.module.auth.domain.AuthTokenClaims
import com.ject.module.auth.domain.TokenType
import org.springframework.dao.DataAccessException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException
import java.util.UUID

/**
 * 회원용 Access Token의 세션이 존재하고 종료되지 않았는지 확인합니다.
 *
 * 세션 조회 중 DB 오류는 인증 실패로 바꾸지 않고 [JwtException]으로 전달합니다. Spring Security가 이를 인증 처리 장애로 바꾸고 [SecurityErrorResponseWriter]가 503으로 응답합니다.
 */
@Component
class AccessTokenSessionValidator(
    private val authSessionRepository: AuthSessionRepository,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        if (token.getClaimAsString(AuthTokenClaims.TOKEN_TYPE) != TokenType.ACCESS.name) {
            return OAuth2TokenValidatorResult.success()
        }

        val sessionId =
            token.getClaimAsString(AuthTokenClaims.SESSION_ID)?.let(::parseUuid)
                ?: return OAuth2TokenValidatorResult.failure(INVALID_SESSION)
        val session = findSession(sessionId)
        if (session == null || session.revokedAt != null || session.memberId.toString() != token.subject) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION)
        }
        return OAuth2TokenValidatorResult.success()
    }

    private fun findSession(sessionId: UUID): AuthSession? =
        try {
            authSessionRepository.findById(sessionId)
        } catch (exception: DataAccessException) {
            throw JwtException("Failed to look up the session", exception)
        } catch (exception: TransactionException) {
            // DB 연결을 얻지 못하면 조회 전에 트랜잭션 시작이 실패합니다.
            throw JwtException("Failed to look up the session", exception)
        }

    private fun parseUuid(value: String): UUID? =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        private val INVALID_SESSION = OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The session is not active", null)
    }
}
