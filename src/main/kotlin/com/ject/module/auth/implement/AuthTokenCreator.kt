package com.ject.module.auth.implement

import com.ject.module.auth.domain.AuthSession
import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.module.auth.domain.AuthTokenClaims
import com.ject.module.auth.domain.AuthTokenResult
import com.ject.module.auth.domain.TokenType
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class AuthTokenCreator(
    private val jwtEncoder: JwtEncoder,
    private val authSessionRepository: AuthSessionRepository,
    private val refreshTokenHasher: RefreshTokenHasher,
    private val clock: Clock,
) {
    /**
     * 회원 세션을 만들고 회원용 Access Token과 Refresh Token을 발급합니다.
     *
     * 세션에는 Refresh Token의 SHA-256 해시만 저장합니다. 세션 저장과 회원 검증을 함께 커밋해야 하면 호출하는 UseCase가 트랜잭션을 엽니다.
     */
    fun createMemberTokens(memberId: Long): AuthTokenResult {
        val now = clock.instant()
        val sessionId = UUID.randomUUID()
        val accessToken = encode(memberId, TokenType.ACCESS, sessionId, now, ACCESS_TOKEN_TTL)
        val refreshToken = encode(memberId, TokenType.REFRESH, sessionId, now, REFRESH_TOKEN_TTL)

        authSessionRepository.save(
            AuthSession(
                id = sessionId,
                memberId = memberId,
                refreshTokenHash = refreshTokenHasher.hash(refreshToken),
                refreshTokenExpiresAt = now.plus(REFRESH_TOKEN_TTL),
            ),
        )

        return AuthTokenResult(accessToken = accessToken, refreshToken = refreshToken)
    }

    /** 가입을 완료하지 않은 회원의 가입 전용 토큰을 발급합니다. 세션을 만들지 않습니다. */
    fun createRegistrationToken(memberId: Long): String =
        encode(memberId, TokenType.REGISTRATION, sessionId = null, issuedAt = clock.instant(), ttl = REGISTRATION_TOKEN_TTL)

    private fun encode(
        memberId: Long,
        tokenType: TokenType,
        sessionId: UUID?,
        issuedAt: Instant,
        ttl: Duration,
    ): String {
        val claims =
            JwtClaimsSet
                .builder()
                .id(UUID.randomUUID().toString())
                .subject(memberId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .claim(AuthTokenClaims.TOKEN_TYPE, tokenType.name)
        if (sessionId != null) {
            claims.claim(AuthTokenClaims.SESSION_ID, sessionId.toString())
        }
        val header = JwsHeader.with(MacAlgorithm.HS512).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).tokenValue
    }

    companion object {
        val ACCESS_TOKEN_TTL: Duration = Duration.ofMinutes(15)
        val REFRESH_TOKEN_TTL: Duration = Duration.ofDays(30)
        val REGISTRATION_TOKEN_TTL: Duration = Duration.ofMinutes(30)
    }
}
