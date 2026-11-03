package com.ject.module.inventory.infra.persistence

import com.ject.module.inventory.domain.Reservation
import com.ject.module.inventory.domain.ReservationRepository
import com.ject.module.inventory.domain.ReservationResult
import com.ject.module.inventory.infra.persistence.jdsl.ReservationJdslRepository
import com.ject.module.inventory.infra.persistence.jpa.ReservationJpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReservationRepositoryAdapter(
    private val reservationJpaRepository: ReservationJpaRepository,
    private val reservationJdslRepository: ReservationJdslRepository,
) : ReservationRepository {
    override fun save(reservation: Reservation): Reservation = reservationJpaRepository.save(reservation)

    override fun findRecentByStockId(stockId: UUID): List<ReservationResult> = reservationJdslRepository.findRecentByStockId(stockId)
}
