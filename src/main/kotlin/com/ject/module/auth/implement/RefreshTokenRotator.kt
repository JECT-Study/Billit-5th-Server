package com.ject.module.auth.implement

import com.ject.module.auth.domain.AuthSession
import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.module.auth.domain.LoginHistoryType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Refresh Token 회전과 토큰 갱신 기록을 함께 처리합니다. */
@Component
class RefreshTokenRotator(
    private val authSessionRepository: AuthSessionRepository,
    private val loginHistoryCreator: LoginHistoryCreator,
) {
    /**
     * 세션 버전이 조회 때와 같고 종료되지 않았으면 Refresh Token을 회전하고 `TOKEN_REFRESH` 기록을 남깁니다. 회전했으면 `true`입니다.
     *
     * 두 쓰기를 한 트랜잭션으로 커밋합니다. 기록 저장이 실패하면 회전도 되돌려, 응답을 받지 못한 앱이 기존 Refresh Token으로 다시 갱신할 수 있게 합니다.
     */
    @Transactional
    fun rotate(
        session: AuthSession,
        newRefreshTokenHash: String,
        rotatedAt: Instant,
    ): Boolean {
        val rotated =
            authSessionRepository.rotateRefreshToken(
                id = session.id,
                expectedVersion = checkNotNull(session.version),
                newRefreshTokenHash = newRefreshTokenHash,
                newRefreshTokenExpiresAt = rotatedAt.plus(AuthTokenCreator.REFRESH_TOKEN_TTL),
                rotatedAt = rotatedAt,
            )
        if (rotated) {
            loginHistoryCreator.create(session.memberId, LoginHistoryType.TOKEN_REFRESH, rotatedAt)
        }
        return rotated
    }
}
