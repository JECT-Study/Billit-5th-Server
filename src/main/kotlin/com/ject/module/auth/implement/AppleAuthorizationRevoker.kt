package com.ject.module.auth.implement

import com.ject.module.auth.domain.AppleRefreshTokenRepository
import com.ject.module.auth.usecase.port.AppleAuthorizationPort
import org.springframework.stereotype.Component

/**
 * 회원의 애플 연결을 해제합니다. 탈퇴 UseCase에서 호출합니다(Apple TN3194).
 *
 * 저장된 애플 Refresh Token을 애플에 폐기 요청하고, 성공하면 저장된 토큰을 삭제합니다.
 * 애플 호출이 DB 트랜잭션을 붙잡지 않도록 호출하는 쪽은 트랜잭션 밖에서 호출합니다. 조회와 삭제는 각각 자체 트랜잭션으로 커밋됩니다.
 */
@Component
class AppleAuthorizationRevoker(
    private val appleRefreshTokenRepository: AppleRefreshTokenRepository,
    private val appleAuthorizationPort: AppleAuthorizationPort,
) {
    /**
     * 저장된 토큰이 있어 연결을 해제했으면 `true`, 저장된 토큰이 없으면 애플을 호출하지 않고 `false`를 반환합니다.
     *
     * 애플이 거절하거나 장애·타임아웃이면 `AUTH_SOCIAL_PROVIDER_UNAVAILABLE`로 실패하고 저장된 토큰을 남겨 다시 시도할 수 있게 합니다.
     */
    fun revoke(memberId: Long): Boolean {
        val refreshToken = appleRefreshTokenRepository.findRefreshToken(memberId) ?: return false
        appleAuthorizationPort.revokeRefreshToken(refreshToken)
        appleRefreshTokenRepository.deleteByMemberId(memberId)
        return true
    }
}
