package com.ject.module.trip.presentation.response

import com.ject.module.trip.domain.TripStatus
import java.time.LocalDate

data class TripInvitationSearchResponse(
    val tripId: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val registeredMemberCount: Int,
    val memberCapacity: Int,
    val status: TripStatus,
    val isAlreadyJoined: Boolean,
)
