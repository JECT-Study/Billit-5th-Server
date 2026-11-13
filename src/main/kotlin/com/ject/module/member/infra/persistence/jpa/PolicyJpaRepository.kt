package com.ject.module.member.infra.persistence.jpa

import com.ject.module.member.domain.Policy
import com.ject.module.member.domain.PolicyType
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface PolicyJpaRepository : JpaRepository<Policy, Long> {
    fun findByTypeInAndEffectiveAtLessThanEqual(
        types: Collection<PolicyType>,
        effectiveAt: Instant,
    ): List<Policy>
}
