package com.ject.module.inventory.usecase

import com.ject.global.error.BusinessException
import com.ject.module.inventory.domain.InventoryErrorCode
import com.ject.module.inventory.domain.ReserveStockCommand
import com.ject.module.inventory.domain.Stock
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.instancio.Instancio
import org.instancio.Select.field
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReserveStockUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var reserveStockUseCase: ReserveStockUseCase

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
