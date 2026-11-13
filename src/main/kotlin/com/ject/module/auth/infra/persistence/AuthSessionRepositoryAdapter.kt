package com.ject.module.auth.infra.persistence

import com.ject.module.auth.domain.AuthSession
import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.module.auth.infra.persistence.jpa.AuthSessionJpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AuthSessionRepositoryAdapter(
    private val authSessionJpaRepository: AuthSessionJpaRepository,
) : AuthSessionRepository {
    override fun save(authSession: AuthSession): AuthSession = authSessionJpaRepository.save(authSession)

    override fun findById(id: UUID): AuthSession? = authSessionJpaRepository.findByIdOrNull(id)
}
