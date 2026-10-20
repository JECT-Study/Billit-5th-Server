package com.ject.module.inventory.presentation

import com.ject.module.inventory.domain.Stock
import com.ject.support.IntegrationTestSupport
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.instancio.Instancio
import org.instancio.Select.field
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

class InventoryControllerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private val stockIds = mutableListOf<UUID>()

    init {
        afterEach {
            stockIds.forEach { id ->
                jdbc.update("DELETE FROM learning_reservations WHERE stock_id = ?", id)
                jdbc.update("DELETE FROM learning_stocks WHERE id = ?", id)
            }
            stockIds.clear()
        }

        test("예약 API는 생성 성공을 반환하고 0개 예약 요청은 저장하지 않는다") {
            // given
            val stockId = createStock(3)
            val mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()

            // when
            val success =
                mvc
                    .perform(
                        post("/api/v1/learning/stocks/$stockId/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"quantity":1}"""),
                    ).andReturn()
                    .response
            val invalid =
                mvc
                    .perform(
                        post("/api/v1/learning/stocks/$stockId/reservations")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"quantity":0}"""),
                    ).andReturn()
                    .response

            // then
            success.status shouldBe 201
            success.getHeader("Location").shouldNotBeNull().startsWith("/api/v1/learning/stocks/$stockId/reservations/") shouldBe true
            invalid.status shouldBe 400
            remaining(stockId) shouldBe 2
            reservationCount(stockId) shouldBe 1
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

    private fun remaining(stockId: UUID): Int =
        checkNotNull(jdbc.queryForObject("SELECT remaining_quantity FROM learning_stocks WHERE id = ?", Int::class.java, stockId))

    private fun reservationCount(stockId: UUID): Int =
        checkNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM learning_reservations WHERE stock_id = ?", Int::class.java, stockId))
}
