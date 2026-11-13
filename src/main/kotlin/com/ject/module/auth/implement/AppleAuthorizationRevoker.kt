package com.ject.module.auth.implement

import com.ject.module.auth.domain.AppleAuthorizationRevokeResult
import com.ject.module.auth.domain.AppleRefreshTokenRepository
import com.ject.module.auth.domain.StoredAppleRefreshToken
import com.ject.module.auth.usecase.port.AppleAuthorizationPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * 회원의 애플 연결을 해제합니다. 탈퇴 UseCase에서 호출합니다(Apple TN3194).
 *
 * 저장된 애플 Refresh Token을 애플에 폐기 요청하고, 성공하면 읽었던 저장값과 같을 때만 저장된 토큰을 삭제합니다.
 * 애플 호출이 DB 트랜잭션을 붙잡지 않도록 호출하는 쪽은 트랜잭션 밖에서 호출합니다. 조회와 삭제는 각각 자체 트랜잭션으로 커밋됩니다.
 */
@Component
class AppleAuthorizationRevoker(
    private val appleRefreshTokenRepository: AppleRefreshTokenRepository,
    private val appleAuthorizationPort: AppleAuthorizationPort,
) {
    /**
     * 저장된 토큰으로 연결을 해제했으면 [AppleAuthorizationRevokeResult.REVOKED]를 반환합니다.
     * 저장된 토큰이 없거나 복호화하지 못하면 애플을 호출하지 않고 각각 [AppleAuthorizationRevokeResult.NOT_FOUND],
     * [AppleAuthorizationRevokeResult.UNDECRYPTABLE]을 반환하며, 복호화하지 못한 행은 지우지 않습니다.
     *
     * 애플이 거절하거나 장애·타임아웃이면 `AUTH_SOCIAL_PROVIDER_UNAVAILABLE`로 실패하고 저장된 토큰을 남겨 다시 시도할 수 있게 합니다.
     * 폐기 요청 중 다시 로그인해 새 토큰이 저장됐으면 새 토큰은 지우지 않습니다.
     */
    fun revoke(memberId: Long): AppleAuthorizationRevokeResult =
        when (val stored = appleRefreshTokenRepository.find(memberId)) {
            null -> {
                AppleAuthorizationRevokeResult.NOT_FOUND
            }

            is StoredAppleRefreshToken.Undecryptable -> {
                AppleAuthorizationRevokeResult.UNDECRYPTABLE
            }

            is StoredAppleRefreshToken.Decrypted -> {
                appleAuthorizationPort.revokeRefreshToken(stored.refreshToken)
                if (!appleRefreshTokenRepository.deleteIfUnchanged(memberId, stored.encryptedRefreshToken)) {
                    log.info { "event=apple_refresh_token_replaced_during_revoke memberId=$memberId" }
                }
                AppleAuthorizationRevokeResult.REVOKED
            }
        }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
