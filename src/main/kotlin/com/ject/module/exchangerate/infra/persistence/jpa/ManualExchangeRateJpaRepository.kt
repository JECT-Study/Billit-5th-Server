package com.ject.module.exchangerate.infra.persistence.jpa

import com.ject.module.exchangerate.domain.ManualExchangeRate
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

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

    @Transactional
    @Modifying
    @Query(
        """
        DELETE FROM ManualExchangeRate r
        WHERE r.id = :id AND r.tripId = :tripId AND r.createdByTripMemberId = :createdByTripMemberId
        """,
    )
    fun deleteRecord(
        @Param("id") id: Long,
        @Param("tripId") tripId: Long,
        @Param("createdByTripMemberId") createdByTripMemberId: Long,
    ): Int
}
