package com.ject.module.auth.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.module.auth.implement.RefreshTokenHasher
import com.ject.module.auth.implement.RefreshTokenReader
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * 현재 기기의 로그인 세션을 종료합니다. 다른 세션은 유지합니다.
 *
 * 종료는 종료되지 않은 세션에만 적용하는 한 번의 조건부 UPDATE이며, 이미 종료된 세션이면 401 `COMMON_UNAUTHORIZED`로 실패합니다.
 * 종료 후 그 세션의 Access Token은 요청 인증의 세션 확인에서, Refresh Token은 토큰 갱신에서 거절됩니다.
 */
@Service
class LogoutUseCase(
    private val refreshTokenReader: RefreshTokenReader,
    private val authSessionRepository: AuthSessionRepository,
    private val refreshTokenHasher: RefreshTokenHasher,
    private val clock: Clock,
) {
    /** 요청 인증에서 서명, 만료 시각과 세션을 확인한 Access Token의 세션을 종료합니다. */
    fun logoutByAccessToken(sessionId: UUID) {
        val revoked = authSessionRepository.revokeIfActive(sessionId, clock.instant())
        if (!revoked) {
            throw BusinessException(CommonErrorCode.UNAUTHORIZED)
        }
    }

    /** Access Token이 만료된 기기가 현재 Refresh Token으로 세션을 종료합니다. 이미 회전해 폐기된 Refresh Token은 받지 않습니다. */
    fun logoutByRefreshToken(refreshToken: String) {
        val tokenSession = refreshTokenReader.read(refreshToken) ?: throw BusinessException(CommonErrorCode.UNAUTHORIZED)
        val session = authSessionRepository.findById(tokenSession.sessionId)
        if (
            session == null ||
            session.revokedAt != null ||
            session.memberId != tokenSession.memberId ||
            !refreshTokenHasher.matches(refreshTokenHasher.hash(refreshToken), session.refreshTokenHash)
        ) {
            throw BusinessException(CommonErrorCode.UNAUTHORIZED)
        }

        val revoked = authSessionRepository.revokeIfActive(session.id, clock.instant())
        if (!revoked) {
            throw BusinessException(CommonErrorCode.UNAUTHORIZED)
        }
    }
}
