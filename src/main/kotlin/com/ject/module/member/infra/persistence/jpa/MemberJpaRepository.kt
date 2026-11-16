package com.ject.module.member.infra.persistence.jpa

import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.Gender
import com.ject.module.member.domain.Member
import com.ject.module.member.domain.MemberStatus
import com.ject.module.member.domain.RegistrationMemberResult
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface MemberJpaRepository : JpaRepository<Member, Long> {
    fun findBySocialProviderAndSocialProviderUserId(
        socialProvider: AuthProvider,
        socialProviderUserId: String,
    ): Member?

    @Transactional
    @Modifying
    @Query(
        """
        UPDATE Member m
        SET m.socialDisplayName = :socialDisplayName,
            m.updatedAt = :updatedAt
        WHERE m.id = :id
          AND m.status = :status
          AND m.socialDisplayName IS NULL
        """,
    )
    fun fillSocialDisplayNameIfAbsent(
        @Param("id") id: Long,
        @Param("status") status: MemberStatus,
        @Param("socialDisplayName") socialDisplayName: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Int

    @Query(
        """
        SELECT new com.ject.module.member.domain.RegistrationMemberResult(m.status, m.socialDisplayName)
        FROM Member m
        WHERE m.id = :id
        """,
    )
    fun findRegistrationMember(
        @Param("id") id: Long,
    ): RegistrationMemberResult?

    fun existsByNickname(nickname: String): Boolean

    @Transactional
    @Modifying
    @Query(
        """
        UPDATE Member m
        SET m.nickname = :nickname,
            m.gender = :gender,
            m.isTreasurer = :isTreasurer,
            m.marketingPushAgreedAt = :marketingPushAgreedAt,
            m.status = :completedStatus,
            m.updatedAt = :updatedAt
        WHERE m.id = :id
          AND m.status = :pendingStatus
        """,
    )
    fun completeRegistrationIfPending(
        @Param("id") id: Long,
        @Param("pendingStatus") pendingStatus: MemberStatus,
        @Param("completedStatus") completedStatus: MemberStatus,
        @Param("nickname") nickname: String,
        @Param("gender") gender: Gender?,
        @Param("isTreasurer") isTreasurer: Boolean?,
        @Param("marketingPushAgreedAt") marketingPushAgreedAt: Instant?,
        @Param("updatedAt") updatedAt: Instant,
    ): Int
}
