package com.ject.module.inventory.usecase

import com.ject.module.inventory.domain.Stock
import com.ject.support.IntegrationTestSupport
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.instancio.Instancio
import org.instancio.Select.field
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class GetRecentReservationsUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var getRecentReservationsUseCase: GetRecentReservationsUseCase

    private val stockIds = mutableListOf<UUID>()

    init {
        afterEach {
            stockIds.forEach { id ->
                jdbc.update("DELETE FROM learning_reservations WHERE stock_id = ?", id)
                jdbc.update("DELETE FROM learning_stocks WHERE id = ?", id)
            }
            stockIds.clear()
        }

        test("최근 예약은 같은 시각이면 식별자 내림차순으로 최대 20건을 반환한다") {
            // given
            val stockId = createStock(30)
            val otherStockId = createStock(30)
            val at = Timestamp.from(Instant.parse("2026-10-20T00:00:00Z"))
            (1L..25L).forEach { value ->
                jdbc.update(
                    "INSERT INTO learning_reservations (id, stock_id, quantity, reserved_at) VALUES (?, ?, ?, ?)",
                    UUID(0L, value),
                    stockId,
                    1,
                    at,
                )
            }
            jdbc.update(
                "INSERT INTO learning_reservations (id, stock_id, quantity, reserved_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(),
                otherStockId,
                1,
                at,
            )

            // when
            val results = getRecentReservationsUseCase.execute(stockId)

            // then
            results.map { it.id } shouldContainExactly (25L downTo 6L).map { UUID(0L, it) }
            results.all { it.stockId == stockId } shouldBe true
        }
    }

    private fun createStock(quantity: Int): UUID {
        val stock =
            Instancio
                .of(Stock::class.java)
                .set(field(Stock::class.java, "remainingQuantity"), quantity)
                .create()
        jdbc.update(
            "INSERT INTO learning_stocks (id, name, remaining_quantity) VALUES (?, ?, ?)",
            stock.id,
            stock.name,
            stock.remainingQuantity,
        )
        stockIds.add(stock.id)
        return stock.id
    }
}
