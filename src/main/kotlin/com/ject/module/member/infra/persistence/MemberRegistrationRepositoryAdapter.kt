package com.ject.module.member.infra.persistence

import com.ject.module.member.domain.MemberConsent
import com.ject.module.member.domain.MemberPainPoint
import com.ject.module.member.domain.MemberRegistrationRepository
import com.ject.module.member.domain.Policy
import com.ject.module.member.domain.PolicyType
import com.ject.module.member.infra.persistence.jpa.MemberConsentJpaRepository
import com.ject.module.member.infra.persistence.jpa.MemberPainPointJpaRepository
import com.ject.module.member.infra.persistence.jpa.PolicyJpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class MemberRegistrationRepositoryAdapter(
    private val policyJpaRepository: PolicyJpaRepository,
    private val memberConsentJpaRepository: MemberConsentJpaRepository,
    private val memberPainPointJpaRepository: MemberPainPointJpaRepository,
) : MemberRegistrationRepository {
    override fun findCurrentSignupPolicies(now: Instant): List<Policy> =
        policyJpaRepository
            .findByTypeInAndEffectiveAtLessThanEqual(PolicyType.SIGNUP_TYPES, now)
            .groupBy { it.type }
            .values
            .map { versions -> versions.maxWith(compareBy<Policy> { it.effectiveAt }.thenBy { checkNotNull(it.id) }) }
            .sortedBy { it.type }

    override fun saveConsents(consents: List<MemberConsent>) {
        memberConsentJpaRepository.saveAll(consents)
    }

    override fun savePainPoints(painPoints: List<MemberPainPoint>) {
        memberPainPointJpaRepository.saveAll(painPoints)
    }
}
