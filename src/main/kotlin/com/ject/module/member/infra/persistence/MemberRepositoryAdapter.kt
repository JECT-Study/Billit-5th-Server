package com.ject.module.member.infra.persistence

import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.Gender
import com.ject.module.member.domain.Member
import com.ject.module.member.domain.MemberRepository
import com.ject.module.member.domain.MemberStatus
import com.ject.module.member.domain.RegistrationCompletionResult
import com.ject.module.member.domain.RegistrationMemberResult
import com.ject.module.member.domain.SocialMemberResult
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
        return SocialMemberResult(memberId = checkNotNull(member.id), status = member.status, socialDisplayName = member.socialDisplayName)
    }

    override fun createPendingMember(
        socialProvider: AuthProvider,
        socialProviderUserId: String,
        socialDisplayName: String?,
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
                socialDisplayName = socialDisplayName?.let(::truncateSocialDisplayName),
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

    override fun fillSocialDisplayNameIfAbsent(
        memberId: Long,
        socialDisplayName: String,
    ): Boolean =
        memberJpaRepository.fillSocialDisplayNameIfAbsent(
            id = memberId,
            status = MemberStatus.PENDING,
            socialDisplayName = truncateSocialDisplayName(socialDisplayName),
            updatedAt = clock.instant(),
        ) == 1

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

    /** PostgreSQL `VARCHAR` 길이는 문자(코드 포인트) 수이므로 서로게이트 쌍을 나누지 않고 자릅니다. */
    private fun truncateSocialDisplayName(socialDisplayName: String): String {
        if (socialDisplayName.codePointCount(0, socialDisplayName.length) <= MemberRepository.SOCIAL_DISPLAY_NAME_MAX_LENGTH) {
            return socialDisplayName
        }
        return socialDisplayName.substring(0, socialDisplayName.offsetByCodePoints(0, MemberRepository.SOCIAL_DISPLAY_NAME_MAX_LENGTH))
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
