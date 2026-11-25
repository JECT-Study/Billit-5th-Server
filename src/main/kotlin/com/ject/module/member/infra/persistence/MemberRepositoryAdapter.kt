package com.ject.module.member.infra.persistence

import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.Gender
import com.ject.module.member.domain.Member
import com.ject.module.member.domain.MemberProfileResult
import com.ject.module.member.domain.MemberRepository
import com.ject.module.member.domain.MemberStatus
import com.ject.module.member.domain.RegistrationCompletionResult
import com.ject.module.member.domain.RegistrationMemberResult
import com.ject.module.member.domain.SocialMemberResult
import com.ject.module.member.domain.WithdrawnMemberResult
import com.ject.module.member.infra.persistence.jpa.MemberJpaRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant

@Repository
class MemberRepositoryAdapter(
    private val memberJpaRepository: MemberJpaRepository,
    private val clock: Clock,
) : MemberRepository {
    override fun findBySocialAccount(
        socialProvider: AuthProvider,
        socialProviderUserId: String,
    ): SocialMemberResult? {
        val member = memberJpaRepository.findBySocialProviderAndSocialProviderUserId(socialProvider, socialProviderUserId) ?: return null
        return SocialMemberResult(memberId = checkNotNull(member.id), status = member.status)
    }

    override fun createPendingMember(
        socialProvider: AuthProvider,
        socialProviderUserId: String,
    ): Long? {
        val member =
            Member(
                socialProvider = socialProvider,
                socialProviderUserId = socialProviderUserId,
                nickname = null,
                name = null,
                age = null,
                gender = null,
                isTreasurer = null,
                marketingPushAgreedAt = null,
                marketingNightPushAgreedAt = null,
                ocrAgreedAt = null,
                status = MemberStatus.PENDING,
                withdrawnAt = null,
            )
        return try {
            checkNotNull(memberJpaRepository.saveAndFlush(member).id)
        } catch (exception: DataIntegrityViolationException) {
            if (!isUniqueViolation(exception, SOCIAL_ACCOUNT_UNIQUE_CONSTRAINT)) {
                throw exception
            }
            null
        }
    }

    override fun findActiveProfile(memberId: Long): MemberProfileResult? = memberJpaRepository.findProfile(memberId, MemberStatus.ACTIVE)

    override fun findRegistrationMember(memberId: Long): RegistrationMemberResult? = memberJpaRepository.findRegistrationMember(memberId)

    override fun completeRegistrationIfPending(
        memberId: Long,
        nickname: String,
        gender: Gender?,
        isTreasurer: Boolean?,
        marketingPushAgreedAt: Instant?,
    ): RegistrationCompletionResult {
        val updated =
            memberJpaRepository.completeRegistrationIfPending(
                id = memberId,
                pendingStatus = MemberStatus.PENDING,
                completedStatus = MemberStatus.ACTIVE,
                nickname = nickname,
                gender = gender,
                isTreasurer = isTreasurer,
                marketingPushAgreedAt = marketingPushAgreedAt,
                updatedAt = clock.instant(),
            )
        return if (updated == 1) RegistrationCompletionResult.COMPLETED else RegistrationCompletionResult.NOT_PENDING
    }

    override fun findWithdrawnMembers(memberIds: Collection<Long>): List<WithdrawnMemberResult> {
        if (memberIds.isEmpty()) {
            return emptyList()
        }
        return memberJpaRepository.findWithdrawnMembers(memberIds)
    }

    private fun isUniqueViolation(
        exception: DataIntegrityViolationException,
        constraintName: String,
    ): Boolean {
        val violation =
            generateSequence(exception.cause) { it.cause }
                .filterIsInstance<ConstraintViolationException>()
                .firstOrNull() ?: return false
        return violation.sqlState == UNIQUE_VIOLATION_SQL_STATE &&
            violation.constraintName.equals(constraintName, ignoreCase = true)
    }

    companion object {
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        private const val SOCIAL_ACCOUNT_UNIQUE_CONSTRAINT = "uq_member_social_provider_social_provider_user_id"
    }
}
