package com.ject.module.auth.domain

import java.util.UUID

interface AuthSessionRepository {
    fun save(authSession: AuthSession): AuthSession

    fun findById(id: UUID): AuthSession?
}
