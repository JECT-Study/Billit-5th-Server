package com.ject.module.trip.infra.persistence.jpa

import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberStatus
import org.springframework.data.jpa.repository.JpaRepository

interface TripMemberJpaRepository : JpaRepository<TripMember, Long> {
    fun findByTripId(tripId: Long): List<TripMember>

    fun findByTripIdAndMemberId(
        tripId: Long,
        memberId: Long,
    ): TripMember?

    fun countByTripIdAndStatusIn(
        tripId: Long,
        statuses: Collection<TripMemberStatus>,
    ): Long
}
