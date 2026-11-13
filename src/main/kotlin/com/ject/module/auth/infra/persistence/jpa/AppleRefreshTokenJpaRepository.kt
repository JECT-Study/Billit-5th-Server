package com.ject.module.auth.infra.persistence.jpa

import com.ject.module.auth.domain.AppleRefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface AppleRefreshTokenJpaRepository : JpaRepository<AppleRefreshToken, Long> {
    fun findByMemberId(memberId: Long): AppleRefreshToken?

    @Transactional
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
        INSERT INTO apple_refresh_token (member_id, encrypted_refresh_token, created_at, updated_at)
        VALUES (:memberId, :encryptedRefreshToken, :savedAt, :savedAt)
        ON CONFLICT ON CONSTRAINT uq_apple_refresh_token_member_id
        DO UPDATE SET encrypted_refresh_token = EXCLUDED.encrypted_refresh_token,
                      updated_at = EXCLUDED.updated_at
        """,
    )
    fun upsert(
        @Param("memberId") memberId: Long,
        @Param("encryptedRefreshToken") encryptedRefreshToken: String,
        @Param("savedAt") savedAt: Instant,
    ): Int

    @Transactional
    @Modifying
    @Query("DELETE FROM AppleRefreshToken t WHERE t.memberId = :memberId")
    fun deleteByMemberIdIfExists(
        @Param("memberId") memberId: Long,
    ): Int
}
