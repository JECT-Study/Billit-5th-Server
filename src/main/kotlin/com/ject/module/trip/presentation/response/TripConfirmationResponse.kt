package com.ject.module.trip.presentation.response

import com.ject.module.trip.domain.TripStatus

data class TripConfirmationResponse(
    val tripId: String,
    val status: TripStatus,
)
