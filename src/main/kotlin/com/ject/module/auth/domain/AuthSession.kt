package com.ject.module.auth.domain

import com.ject.global.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "auth_session")
class AuthSession(
    @field:Id
    @field:Column(name = "id")
    val id: UUID,
    @field:Column(name = "member_id", nullable = false)
    val memberId: Long,
    refreshTokenHash: String,
    refreshTokenExpiresAt: Instant,
) : BaseEntity() {
    @field:Column(name = "refresh_token_hash", nullable = false)
    var refreshTokenHash: String = refreshTokenHash
        protected set

    @field:Column(name = "refresh_token_expires_at", nullable = false)
    var refreshTokenExpiresAt: Instant = refreshTokenExpiresAt
        protected set

    @field:Column(name = "previous_refresh_token_hash", nullable = true)
    var previousRefreshTokenHash: String? = null
        protected set

    @field:Column(name = "refresh_token_rotated_at", nullable = true)
    var refreshTokenRotatedAt: Instant? = null
        protected set

    @field:Column(name = "revoked_at", nullable = true)
    var revokedAt: Instant? = null
        protected set

    /**
     * 동시에 들어온 Refresh Token 회전과 세션 종료 중 하나만 반영되도록 막습니다.
     *
     * 식별자를 직접 할당하므로 Spring Data는 이 값이 `null`인지로 새 Entity를 판단해 `save()`에서 merge 대신 persist를 실행합니다.
     */
    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set
}
