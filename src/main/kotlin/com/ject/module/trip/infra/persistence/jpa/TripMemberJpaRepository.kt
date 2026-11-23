package com.ject.module.trip.infra.persistence.jpa

import com.ject.module.trip.domain.TripMember
import org.springframework.data.jpa.repository.JpaRepository

interface TripMemberJpaRepository : JpaRepository<TripMember, Long> {
    fun findByTripId(tripId: Long): List<TripMember>
}
