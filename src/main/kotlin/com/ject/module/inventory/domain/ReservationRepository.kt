package com.ject.module.inventory.domain

import java.util.UUID

interface ReservationRepository {
    fun save(reservation: Reservation): Reservation

    fun findRecentByStockId(stockId: UUID): List<ReservationResult>
}
