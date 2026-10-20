package com.ject.module.inventory.usecase

import com.ject.module.inventory.domain.ReservationRepository
import com.ject.module.inventory.domain.ReservationResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class GetRecentReservationsUseCase(
    private val reservationRepository: ReservationRepository,
) {
    @Transactional(readOnly = true)
    fun execute(stockId: UUID): List<ReservationResult> = reservationRepository.findRecentByStockId(stockId)
}
