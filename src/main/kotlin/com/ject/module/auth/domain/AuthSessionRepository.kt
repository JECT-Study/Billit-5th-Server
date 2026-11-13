package com.ject.module.auth.domain

import java.time.Instant
import java.util.UUID

interface AuthSessionRepository {
    fun save(authSession: AuthSession): AuthSession

    fun findById(id: UUID): AuthSession?

    /**
     * 세션의 버전이 [expectedVersion]이고 종료되지 않았을 때만 Refresh Token을 회전합니다.
     *
     * 현재 해시를 직전 해시로 옮기고 새 해시, 만료 시각과 회전 시각을 기록한 뒤 버전을 올립니다. 그 사이 다른 회전이나 종료가 반영됐으면 변경하지 않고 `false`를 반환합니다.
     * 자체 트랜잭션에서 실행합니다.
     */
    fun rotateRefreshToken(
        id: UUID,
        expectedVersion: Long,
        newRefreshTokenHash: String,
        newRefreshTokenExpiresAt: Instant,
        rotatedAt: Instant,
    ): Boolean

    /**
     * 종료되지 않은 세션을 [revokedAt]에 종료하고 버전을 올립니다. 이미 종료됐거나 세션이 없으면 `false`를 반환합니다.
     *
     * 버전과 관계없이 종료하므로 동시에 진행 중인 회전보다 우선합니다. 자체 트랜잭션에서 실행합니다.
     */
    fun revokeIfActive(
        id: UUID,
        revokedAt: Instant,
    ): Boolean
}
