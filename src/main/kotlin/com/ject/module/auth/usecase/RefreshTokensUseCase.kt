package com.ject.module.auth.usecase

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.domain.AuthSession
import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.module.auth.domain.AuthTokenResult
import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.module.auth.implement.RefreshTokenHasher
import com.ject.module.auth.implement.RefreshTokenReader
import com.ject.module.auth.infra.RefreshTokenProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

/**
 * Refresh Token을 회전해 같은 세션의 새 Access Token과 Refresh Token을 발급합니다.
 *
 * - 현재 Refresh Token이면 새 토큰 쌍을 발급하고 기존 토큰을 직전 토큰으로 옮깁니다.
 * - 직전 Refresh Token이 회전 후 [RefreshTokenProperties.retryGracePeriod] 안에 다시 오면 응답을 받지 못한 재시도로 보고 한 번 더 회전합니다.
 *   이때 처음 토큰은 직전 토큰 자리에서 밀려나므로 같은 토큰의 두 번째 재시도는 재사용으로 처리됩니다.
 * - 그 밖의 이미 회전된 토큰은 재사용으로 보고 세션을 종료합니다. 같은 세션의 Access Token도 함께 401이 됩니다.
 *
 * 회전과 종료는 각각 한 번의 조건부 UPDATE이며 자체 트랜잭션으로 커밋합니다. 재사용으로 세션을 종료한 뒤 401로 응답해야 하므로 UseCase 전체를 트랜잭션으로 묶지 않습니다.
 * 동시에 들어온 갱신은 세션 버전 조건으로 한 건만 회전하고 나머지는 401로 거절합니다.
 */
@Service
class RefreshTokensUseCase(
    private val refreshTokenReader: RefreshTokenReader,
    private val authSessionRepository: AuthSessionRepository,
    private val refreshTokenHasher: RefreshTokenHasher,
    private val authTokenCreator: AuthTokenCreator,
    private val refreshTokenProperties: RefreshTokenProperties,
    private val clock: Clock,
) {
    fun refresh(refreshToken: String): AuthTokenResult {
        val tokenSession = refreshTokenReader.read(refreshToken) ?: throw invalidRefreshToken()
        val session = authSessionRepository.findById(tokenSession.sessionId)
        if (session == null || session.revokedAt != null || session.memberId != tokenSession.memberId) {
            throw invalidRefreshToken()
        }

        val now = clock.instant()
        val tokenHash = refreshTokenHasher.hash(refreshToken)
        val isCurrentToken = hashEquals(tokenHash, session.refreshTokenHash)
        if (isCurrentToken && !now.isBefore(session.refreshTokenExpiresAt)) {
            throw invalidRefreshToken()
        }
        if (!isCurrentToken && !isRetryWithPreviousToken(session, tokenHash, now)) {
            val revoked = authSessionRepository.revokeIfActive(session.id, now)
            log.warn { "event=refresh_token_reuse_detected sessionId=${session.id} revoked=$revoked" }
            throw invalidRefreshToken()
        }

        val tokens = authTokenCreator.createSessionTokens(session.memberId, session.id, now)
        val rotated =
            authSessionRepository.rotateRefreshToken(
                id = session.id,
                expectedVersion = checkNotNull(session.version),
                newRefreshTokenHash = refreshTokenHasher.hash(tokens.refreshToken),
                newRefreshTokenExpiresAt = now.plus(AuthTokenCreator.REFRESH_TOKEN_TTL),
                rotatedAt = now,
            )
        if (!rotated) {
            // 조회 후 다른 갱신이나 로그아웃이 먼저 반영됐습니다. 재사용 공격으로 단정할 수 없어 세션은 그대로 둡니다.
            throw invalidRefreshToken()
        }
        return tokens
    }

    private fun isRetryWithPreviousToken(
        session: AuthSession,
        tokenHash: String,
        now: Instant,
    ): Boolean {
        val previousHash = session.previousRefreshTokenHash
        val rotatedAt = session.refreshTokenRotatedAt
        if (previousHash == null || rotatedAt == null) {
            return false
        }
        return hashEquals(tokenHash, previousHash) && now.isBefore(rotatedAt.plus(refreshTokenProperties.retryGracePeriod))
    }

    private fun hashEquals(
        left: String,
        right: String,
    ): Boolean = MessageDigest.isEqual(left.toByteArray(), right.toByteArray())

    private fun invalidRefreshToken() = BusinessException(AuthErrorCode.REFRESH_TOKEN_INVALID)

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
