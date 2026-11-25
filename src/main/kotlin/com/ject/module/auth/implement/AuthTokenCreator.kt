package com.ject.module.auth.implement

import com.ject.module.auth.domain.AuthSession
import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.module.auth.domain.AuthTokenClaims
import com.ject.module.auth.domain.AuthTokenResult
import com.ject.module.auth.domain.LoginHistoryType
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
    private val loginHistoryCreator: LoginHistoryCreator,
    private val clock: Clock,
) {
    /**
     * 회원 세션을 만들고 회원용 Access Token과 Refresh Token을 발급합니다.
     *
     * 세션에는 Refresh Token의 SHA-256 해시만 저장합니다. 새 세션은 로그인이므로 로그인 기록도 남깁니다.
     * 세션과 로그인 기록을 함께 커밋해야 하므로 호출하는 UseCase가 트랜잭션을 엽니다.
     */
    fun createMemberTokens(memberId: Long): AuthTokenResult {
        val now = clock.instant()
        val sessionId = UUID.randomUUID()
        val tokens = createSessionTokens(memberId, sessionId, now)

        authSessionRepository.save(
            AuthSession(
                id = sessionId,
                memberId = memberId,
                refreshTokenHash = refreshTokenHasher.hash(tokens.refreshToken),
                refreshTokenExpiresAt = now.plus(REFRESH_TOKEN_TTL),
            ),
        )
        loginHistoryCreator.create(memberId, LoginHistoryType.LOGIN, now)

        return tokens
    }

    /**
     * 기존 세션의 회원용 Access Token과 Refresh Token을 새로 서명합니다. 세션을 저장하거나 변경하지 않습니다.
     *
     * Refresh Token 만료 시각은 [issuedAt]에 [REFRESH_TOKEN_TTL]을 더한 값입니다. 세션 갱신은 호출하는 쪽이 같은 시각으로 처리합니다.
     */
    fun createSessionTokens(
        memberId: Long,
        sessionId: UUID,
        issuedAt: Instant,
    ): AuthTokenResult =
        AuthTokenResult(
            accessToken = encode(memberId, TokenType.ACCESS, sessionId, issuedAt, ACCESS_TOKEN_TTL),
            refreshToken = encode(memberId, TokenType.REFRESH, sessionId, issuedAt, REFRESH_TOKEN_TTL),
        )

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
