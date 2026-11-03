package com.ject.module.inventory.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "learning_reservations")
class Reservation(
    @Id val id: UUID,
    val stockId: UUID,
    val quantity: Int,
    val reservedAt: Instant,
)
