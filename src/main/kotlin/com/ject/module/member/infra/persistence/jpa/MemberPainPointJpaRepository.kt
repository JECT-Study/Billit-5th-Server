package com.ject.module.member.infra.persistence.jpa

import com.ject.module.member.domain.MemberPainPoint
import org.springframework.data.jpa.repository.JpaRepository

interface MemberPainPointJpaRepository : JpaRepository<MemberPainPoint, Long>
