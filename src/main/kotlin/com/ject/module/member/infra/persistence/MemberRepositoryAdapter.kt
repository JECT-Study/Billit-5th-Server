package com.ject.module.member.infra.persistence

import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.Member
import com.ject.module.member.domain.MemberRepository
import com.ject.module.member.domain.MemberStatus
import com.ject.module.member.domain.SocialMemberResult
import com.ject.module.member.infra.persistence.jpa.MemberJpaRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

@Repository
class MemberRepositoryAdapter(
    private val memberJpaRepository: MemberJpaRepository,
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
            if (!isSocialAccountUniqueViolation(exception)) {
                throw exception
            }
            null
        }
    }

    private fun isSocialAccountUniqueViolation(exception: DataIntegrityViolationException): Boolean {
        val violation =
            generateSequence(exception.cause) { it.cause }
                .filterIsInstance<ConstraintViolationException>()
                .firstOrNull() ?: return false
        return violation.sqlState == UNIQUE_VIOLATION_SQL_STATE &&
            violation.constraintName.equals(SOCIAL_ACCOUNT_UNIQUE_CONSTRAINT, ignoreCase = true)
    }

    companion object {
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        private const val SOCIAL_ACCOUNT_UNIQUE_CONSTRAINT = "uq_member_social_provider_social_provider_user_id"
    }
}
