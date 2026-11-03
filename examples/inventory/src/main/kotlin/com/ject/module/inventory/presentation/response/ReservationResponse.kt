package com.ject.module.inventory.presentation.response

import java.time.Instant

data class ReservationResponse(
    val id: String,
    val stockId: String,
    val quantity: Int,
    val reservedAt: Instant,
)
