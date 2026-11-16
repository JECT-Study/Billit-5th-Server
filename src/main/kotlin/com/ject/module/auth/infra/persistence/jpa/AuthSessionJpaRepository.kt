package com.ject.module.auth.infra.persistence.jpa

import com.ject.module.auth.domain.AuthSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

interface AuthSessionJpaRepository : JpaRepository<AuthSession, UUID> {
    @Transactional
    @Modifying
    @Query(
        """
        UPDATE AuthSession s
        SET s.previousRefreshTokenHash = s.refreshTokenHash,
            s.refreshTokenHash = :newRefreshTokenHash,
            s.refreshTokenExpiresAt = :newRefreshTokenExpiresAt,
            s.refreshTokenRotatedAt = :rotatedAt,
            s.updatedAt = :rotatedAt,
            s.version = s.version + 1
        WHERE s.id = :id
          AND s.version = :expectedVersion
          AND s.revokedAt IS NULL
        """,
    )
    fun rotateRefreshToken(
        @Param("id") id: UUID,
        @Param("expectedVersion") expectedVersion: Long,
        @Param("newRefreshTokenHash") newRefreshTokenHash: String,
        @Param("newRefreshTokenExpiresAt") newRefreshTokenExpiresAt: Instant,
        @Param("rotatedAt") rotatedAt: Instant,
    ): Int

    @Transactional
    @Modifying
    @Query(
        """
        UPDATE AuthSession s
        SET s.revokedAt = :revokedAt,
            s.updatedAt = :revokedAt,
            s.version = s.version + 1
        WHERE s.id = :id
          AND s.revokedAt IS NULL
        """,
    )
    fun revokeIfActive(
        @Param("id") id: UUID,
        @Param("revokedAt") revokedAt: Instant,
    ): Int
}
