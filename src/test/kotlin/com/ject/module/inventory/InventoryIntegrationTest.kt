package com.ject.module.inventory

import com.ject.global.error.BusinessException
import com.ject.module.inventory.domain.InventoryErrorCode
import com.ject.module.inventory.domain.ReserveStockCommand
import com.ject.module.inventory.usecase.GetRecentReservationsUseCase
import com.ject.module.inventory.usecase.ReserveStockUseCase
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InventoryIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var reserveStockUseCase: ReserveStockUseCase

    @Autowired
    private lateinit var getRecentReservationsUseCase: GetRecentReservationsUseCase

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

        test("예약이 성공하면 재고를 차감하고 예약을 함께 저장한다") {
            // given
            val stockId = createStock(5)

            // when
            val result = reserveStockUseCase.execute(ReserveStockCommand(stockId, 2))

            // then
            remaining(stockId) shouldBe 3
            reservationCount(stockId) shouldBe 1
            jdbc.queryForObject(
                "SELECT quantity FROM learning_reservations WHERE id = ?",
                Int::class.java,
                result.id,
            ) shouldBe 2
        }

        test("재고가 부족하면 재고와 예약을 변경하지 않는다") {
            // given
            val stockId = createStock(1)

            // when
            val error =
                shouldThrow<BusinessException> {
                    reserveStockUseCase.execute(ReserveStockCommand(stockId, 2))
                }

            // then
            error.errorCode shouldBe InventoryErrorCode.INSUFFICIENT_STOCK
            remaining(stockId) shouldBe 1
            reservationCount(stockId) shouldBe 0
        }

        test("동시에 마지막 재고를 예약하면 한 요청만 성공한다") {
            // given
            val stockId = createStock(1)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)

            try {
                val requests =
                    List(2) {
                        executor.submit(
                            Callable {
                                ready.countDown()
                                check(start.await(10, TimeUnit.SECONDS))
                                runCatching { reserveStockUseCase.execute(ReserveStockCommand(stockId, 1)) }
                            },
                        )
                    }

                // when
                ready.await(10, TimeUnit.SECONDS) shouldBe true
                start.countDown()
                val results = requests.map { it.get(30, TimeUnit.SECONDS) }

                // then
                results.count { it.isSuccess } shouldBe 1
                val failure = results.single { it.isFailure }.exceptionOrNull()
                (failure as BusinessException).errorCode shouldBe InventoryErrorCode.INSUFFICIENT_STOCK
                remaining(stockId) shouldBe 0
                reservationCount(stockId) shouldBe 1
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        }

        test("재고 갱신이 DB에서 실패하면 앞서 저장한 예약도 롤백한다") {
            // given
            val stockId = createStock(2)
            jdbc.execute(
                "ALTER TABLE learning_stocks ADD CONSTRAINT learning_test_update_failure " +
                    "CHECK (id <> '$stockId'::uuid OR remaining_quantity <> 1)",
            )

            try {
                // when
                val error =
                    shouldThrow<RuntimeException> {
                        reserveStockUseCase.execute(ReserveStockCommand(stockId, 1))
                    }

                // then
                val sqlError =
                    generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<SQLException>()
                        .first()
                sqlError.sqlState shouldBe "23514"
                remaining(stockId) shouldBe 2
                reservationCount(stockId) shouldBe 0
            } finally {
                jdbc.execute("ALTER TABLE learning_stocks DROP CONSTRAINT learning_test_update_failure")
            }
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
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO learning_stocks (id, name, remaining_quantity) VALUES (?, ?, ?)", id, "학습용 상품", quantity)
        stockIds.add(id)
        return id
    }

    private fun remaining(stockId: UUID): Int =
        jdbc.queryForObject("SELECT remaining_quantity FROM learning_stocks WHERE id = ?", Int::class.java, stockId).shouldNotBeNull()

    private fun reservationCount(stockId: UUID): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM learning_reservations WHERE stock_id = ?", Int::class.java, stockId).shouldNotBeNull()
}
