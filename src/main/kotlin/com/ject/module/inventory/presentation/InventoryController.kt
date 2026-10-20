package com.ject.module.inventory.presentation

import com.ject.global.response.ApiResponse
import com.ject.module.inventory.domain.ReserveStockCommand
import com.ject.module.inventory.presentation.request.ReserveStockRequest
import com.ject.module.inventory.presentation.response.ReservationResponse
import com.ject.module.inventory.usecase.GetRecentReservationsUseCase
import com.ject.module.inventory.usecase.ReserveStockUseCase
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/learning/stocks")
class InventoryController(
    private val reserveStockUseCase: ReserveStockUseCase,
    private val getRecentReservationsUseCase: GetRecentReservationsUseCase,
) {
    @PostMapping("/{stockId}/reservations")
    fun reserve(
        @PathVariable stockId: UUID,
        @Valid @RequestBody request: ReserveStockRequest,
    ): ResponseEntity<ApiResponse<ReservationResponse>> {
        val result = reserveStockUseCase.execute(ReserveStockCommand(stockId, request.quantity))

        return ResponseEntity
            .created(URI.create("/api/v1/learning/stocks/$stockId/reservations/${result.id}"))
            .body(
                ApiResponse(
                    data =
                        ReservationResponse(
                            id = result.id.toString(),
                            stockId = result.stockId.toString(),
                            quantity = result.quantity,
                            reservedAt = result.reservedAt,
                        ),
                ),
            )
    }

    @GetMapping("/{stockId}/reservations")
    fun getRecent(
        @PathVariable stockId: UUID,
    ): ApiResponse<List<ReservationResponse>> {
        val results = getRecentReservationsUseCase.execute(stockId)

        return ApiResponse(
            data =
                results.map { result ->
                    ReservationResponse(
                        id = result.id.toString(),
                        stockId = result.stockId.toString(),
                        quantity = result.quantity,
                        reservedAt = result.reservedAt,
                    )
                },
        )
    }
}
