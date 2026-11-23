package com.ject.module.auth.infra.persistence

import com.ject.module.auth.domain.LoginHistory
import com.ject.module.auth.domain.LoginHistoryRepository
import com.ject.module.auth.infra.persistence.jpa.LoginHistoryJpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class LoginHistoryRepositoryAdapter(
    private val loginHistoryJpaRepository: LoginHistoryJpaRepository,
) : LoginHistoryRepository {
    override fun save(loginHistory: LoginHistory): LoginHistory = loginHistoryJpaRepository.save(loginHistory)

    override fun deleteOccurredBefore(cutoff: Instant): Int = loginHistoryJpaRepository.deleteOccurredBefore(cutoff)
}
