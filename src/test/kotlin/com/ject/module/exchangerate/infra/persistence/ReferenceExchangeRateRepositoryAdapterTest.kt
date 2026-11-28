package com.ject.module.exchangerate.infra.persistence

import com.ject.module.exchangerate.domain.ReferenceExchangeRateRepository
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ReferenceExchangeRateRepositoryAdapterTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var referenceExchangeRateRepository: ReferenceExchangeRateRepository

    private val provider = "TEST_ECB"
    private val rateDate = LocalDate.parse("2026-11-07")
    private val createdAt = Instant.parse("2026-11-09T01:00:00Z")

    init {
        afterEach {
            jdbc.update("DELETE FROM reference_exchange_rate WHERE provider = ?", provider)
        }

        test("행을 저장하면 반올림하지 않은 환율과 기준일, 결제일을 다시 읽을 수 있다") {
            // given
            val rate = BigDecimal("9.090912345678")

            // when
            val saved =
                referenceExchangeRateRepository.saveIfAbsent(
                    provider,
                    "JPY",
                    rateDate,
                    LocalDate.parse("2026-11-06"),
                    rate,
                    createdAt,
                )

            // then
            val found = referenceExchangeRateRepository.find(provider, "JPY", rateDate)
            assertSoftly {
                saved shouldBe true
                found?.rate?.compareTo(rate) shouldBe 0
                found?.rateDate shouldBe rateDate
                found?.baseDate shouldBe LocalDate.parse("2026-11-06")
                found?.createdAt shouldBe createdAt
                jdbc.queryForObject(
                    "SELECT rate::text FROM reference_exchange_rate WHERE provider = ?",
                    String::class.java,
                    provider,
                ) shouldBe
                    "9.090912345678"
            }
        }

        test("같은 고시 기관, 통화와 결제일의 행이 있으면 저장하지 않고 기존 행을 바꾸지 않는다") {
            // given
            referenceExchangeRateRepository.saveIfAbsent(provider, "JPY", rateDate, rateDate, BigDecimal("9.1111"), createdAt)

            // when
            val saved =
                referenceExchangeRateRepository.saveIfAbsent(
                    provider,
                    "JPY",
                    rateDate,
                    rateDate.minusDays(1),
                    BigDecimal("9.2222"),
                    createdAt.plusSeconds(60),
                )

            // then
            val found = referenceExchangeRateRepository.find(provider, "JPY", rateDate)
            assertSoftly {
                saved shouldBe false
                found?.rate?.compareTo(BigDecimal("9.1111")) shouldBe 0
                found?.baseDate shouldBe rateDate
                found?.createdAt shouldBe createdAt
                jdbc.queryForObject("SELECT count(*) FROM reference_exchange_rate WHERE provider = ?", Long::class.java, provider) shouldBe
                    1L
            }
        }

        test("통화나 결제일이 다르면 별개 행으로 저장한다") {
            // when
            val jpy = referenceExchangeRateRepository.saveIfAbsent(provider, "JPY", rateDate, rateDate, BigDecimal("9.11"), createdAt)
            val usd = referenceExchangeRateRepository.saveIfAbsent(provider, "USD", rateDate, rateDate, BigDecimal("1358.73"), createdAt)
            val nextDay =
                referenceExchangeRateRepository.saveIfAbsent(
                    provider,
                    "JPY",
                    rateDate.plusDays(1),
                    rateDate,
                    BigDecimal("9.11"),
                    createdAt,
                )

            // then
            assertSoftly {
                jpy shouldBe true
                usd shouldBe true
                nextDay shouldBe true
                jdbc.queryForObject("SELECT count(*) FROM reference_exchange_rate WHERE provider = ?", Long::class.java, provider) shouldBe
                    3L
            }
        }

        test("같은 행을 동시에 저장해도 한 행만 저장하고 모든 요청이 오류 없이 끝난다") {
            // given
            val threads = 8
            val executor = Executors.newFixedThreadPool(threads)
            val ready = CountDownLatch(threads)
            val start = CountDownLatch(1)

            // when
            val futures =
                (1..threads).map { index ->
                    executor.submit<Boolean> {
                        ready.countDown()
                        start.await()
                        referenceExchangeRateRepository.saveIfAbsent(provider, "JPY", rateDate, rateDate, BigDecimal("9.$index"), createdAt)
                    }
                }
            ready.await()
            start.countDown()
            val results = futures.map { it.get() }
            executor.shutdown()

            // then
            assertSoftly {
                results.count { it } shouldBe 1
                jdbc.queryForObject("SELECT count(*) FROM reference_exchange_rate WHERE provider = ?", Long::class.java, provider) shouldBe
                    1L
            }
        }

        test("기준일이 결제일보다 7일 이전이면 저장한다") {
            // when
            val saved =
                referenceExchangeRateRepository.saveIfAbsent(
                    provider,
                    "JPY",
                    rateDate,
                    rateDate.minusDays(7),
                    BigDecimal("9.11"),
                    createdAt,
                )

            // then
            saved shouldBe true
        }

        test("기준일이 결제일보다 8일 이전이면 CHECK 제약이 거절한다") {
            // when
            val exception =
                shouldThrow<DataIntegrityViolationException> {
                    referenceExchangeRateRepository.saveIfAbsent(
                        provider,
                        "JPY",
                        rateDate,
                        rateDate.minusDays(8),
                        BigDecimal("9.11"),
                        createdAt,
                    )
                }

            // then
            assertSoftly {
                exception.message?.contains("ck_reference_exchange_rate_base_date_range") shouldBe true
                jdbc.queryForObject("SELECT count(*) FROM reference_exchange_rate WHERE provider = ?", Long::class.java, provider) shouldBe
                    0L
            }
        }

        test("기준일이 결제일보다 이후이면 CHECK 제약이 거절한다") {
            // when
            val exception =
                shouldThrow<DataIntegrityViolationException> {
                    referenceExchangeRateRepository.saveIfAbsent(
                        provider,
                        "JPY",
                        rateDate,
                        rateDate.plusDays(1),
                        BigDecimal("9.11"),
                        createdAt,
                    )
                }

            // then
            exception.message?.contains("ck_reference_exchange_rate_base_date_range") shouldBe true
        }
    }
}
