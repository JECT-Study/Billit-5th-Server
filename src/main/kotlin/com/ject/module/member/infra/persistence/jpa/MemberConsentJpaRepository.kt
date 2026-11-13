package com.ject.module.member.infra.persistence.jpa

import com.ject.module.member.domain.MemberConsent
import org.springframework.data.jpa.repository.JpaRepository

interface MemberConsentJpaRepository : JpaRepository<MemberConsent, Long>
