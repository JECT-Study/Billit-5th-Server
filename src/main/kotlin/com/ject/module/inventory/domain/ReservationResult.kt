package com.ject.module.inventory.domain

import java.time.Instant
import java.util.UUID

data class ReservationResult(
    val id: UUID,
    val stockId: UUID,
    val quantity: Int,
    val reservedAt: Instant,
)
