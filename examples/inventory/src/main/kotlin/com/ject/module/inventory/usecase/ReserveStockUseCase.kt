package com.ject.module.inventory.usecase

import com.ject.global.error.BusinessException
import com.ject.module.inventory.domain.InventoryErrorCode
import com.ject.module.inventory.domain.Reservation
import com.ject.module.inventory.domain.ReservationRepository
import com.ject.module.inventory.domain.ReservationResult
import com.ject.module.inventory.domain.ReserveStockCommand
import com.ject.module.inventory.domain.StockRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class ReserveStockUseCase(
    private val stockRepository: StockRepository,
    private val reservationRepository: ReservationRepository,
    private val clock: Clock,
) {
    @Transactional
    fun execute(command: ReserveStockCommand): ReservationResult {
        if (command.quantity < 1) {
            throw BusinessException(InventoryErrorCode.INVALID_QUANTITY)
        }

        // 잠금 이후의 최신 재고로 판단하며, 재고 차감과 예약 저장은 함께 커밋합니다.
        val stock =
            stockRepository.findByIdForUpdate(command.stockId)
                ?: throw BusinessException(InventoryErrorCode.STOCK_NOT_FOUND)

        if (stock.remainingQuantity < command.quantity) {
            throw BusinessException(InventoryErrorCode.INSUFFICIENT_STOCK)
        }

        stock.decrease(command.quantity)
        val reservation =
            reservationRepository.save(
                Reservation(
                    id = UUID.randomUUID(),
                    stockId = stock.id,
                    quantity = command.quantity,
                    reservedAt = Instant.now(clock),
                ),
            )

        return ReservationResult(
            id = reservation.id,
            stockId = reservation.stockId,
            quantity = reservation.quantity,
            reservedAt = reservation.reservedAt,
        )
    }
}
