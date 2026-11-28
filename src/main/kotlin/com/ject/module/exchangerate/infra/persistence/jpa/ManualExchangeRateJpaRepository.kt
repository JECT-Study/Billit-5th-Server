package com.ject.module.exchangerate.infra.persistence.jpa

import com.ject.module.exchangerate.domain.ManualExchangeRate
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ManualExchangeRateJpaRepository : JpaRepository<ManualExchangeRate, Long> {
    fun findByTripIdAndCreatedByTripMemberId(
        tripId: Long,
        createdByTripMemberId: Long,
        pageable: Pageable,
    ): List<ManualExchangeRate>

    fun countByTripIdAndCreatedByTripMemberId(
        tripId: Long,
        createdByTripMemberId: Long,
    ): Long
}
