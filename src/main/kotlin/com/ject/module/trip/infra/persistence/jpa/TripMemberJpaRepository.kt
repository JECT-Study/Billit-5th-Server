package com.ject.module.trip.infra.persistence.jpa

import com.ject.module.trip.domain.TripMember
import org.springframework.data.jpa.repository.JpaRepository

interface TripMemberJpaRepository : JpaRepository<TripMember, Long> {
    fun findByTripIdAndMemberId(
        tripId: Long,
        memberId: Long,
    ): TripMember?

    fun countByTripId(tripId: Long): Long
}
