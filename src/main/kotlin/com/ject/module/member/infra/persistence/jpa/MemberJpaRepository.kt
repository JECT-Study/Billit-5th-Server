package com.ject.module.member.infra.persistence.jpa

import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.Member
import org.springframework.data.jpa.repository.JpaRepository

interface MemberJpaRepository : JpaRepository<Member, Long> {
    fun findBySocialProviderAndSocialProviderUserId(
        socialProvider: AuthProvider,
        socialProviderUserId: String,
    ): Member?
}
