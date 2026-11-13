package com.ject.module.auth.infra.persistence.jpa

import com.ject.module.auth.domain.AuthSession
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AuthSessionJpaRepository : JpaRepository<AuthSession, UUID>
