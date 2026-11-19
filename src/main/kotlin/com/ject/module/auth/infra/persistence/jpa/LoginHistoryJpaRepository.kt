package com.ject.module.auth.infra.persistence.jpa

import com.ject.module.auth.domain.LoginHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

interface LoginHistoryJpaRepository : JpaRepository<LoginHistory, Long> {
    @Transactional
    @Modifying
    @Query("DELETE FROM LoginHistory h WHERE h.occurredAt < :cutoff")
    fun deleteOccurredBefore(
        @Param("cutoff") cutoff: Instant,
    ): Int
}
