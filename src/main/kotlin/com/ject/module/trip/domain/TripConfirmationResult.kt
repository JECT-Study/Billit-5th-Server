package com.ject.module.trip.domain

/** 확정 요청을 처리한 뒤의 여행 확정 상태입니다. */
data class TripConfirmationResult(
    val tripId: Long,
    val status: TripStatus,
)
