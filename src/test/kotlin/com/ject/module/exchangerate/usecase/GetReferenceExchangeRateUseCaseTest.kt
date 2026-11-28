package com.ject.module.exchangerate.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.exchangerate.domain.ExchangeRateErrorCode
import com.ject.module.exchangerate.domain.PublishedExchangeRate
import com.ject.module.exchangerate.domain.ReferenceExchangeRate
import com.ject.module.exchangerate.domain.ReferenceExchangeRateRepository
import com.ject.module.exchangerate.usecase.port.ReferenceRateProviderPort
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class GetReferenceExchangeRateUseCaseTest :
    FunSpec({
        // 서버 날짜(Asia/Seoul)는 2026-11-09(월)입니다. UTC 2026-11-08T15:30은 서울 2026-11-09 00:30입니다.
        val now = Instant.parse("2026-11-08T15:30:00Z")
        val today = LocalDate.parse("2026-11-09")

        class FakeRepository : ReferenceExchangeRateRepository {
            val rows = mutableListOf<ReferenceExchangeRate>()

            override fun find(
                provider: String,
                currency: String,
                rateDate: LocalDate,
            ) = rows.firstOrNull { it.provider == provider && it.currency == currency && it.rateDate == rateDate }

            override fun saveIfAbsent(
                provider: String,
                currency: String,
                rateDate: LocalDate,
                baseDate: LocalDate,
                rate: BigDecimal,
                createdAt: Instant,
            ): Boolean {
                if (find(provider, currency, rateDate) != null) {
                    return false
                }
                rows.add(ReferenceExchangeRate(provider, currency, rateDate, baseDate, rate, createdAt))
                return true
            }
        }

        class FakePort(
            private val published: PublishedExchangeRate?,
            private val failure: BusinessException? = null,
        ) : ReferenceRateProviderPort {
            var calls = 0
            override val provider = "ECB"

            override fun fetch(
                currency: String,
                date: LocalDate,
            ): PublishedExchangeRate? {
                calls++
                failure?.let { throw it }
                return published
            }
        }

        fun useCase(
            repository: FakeRepository,
            port: FakePort,
            clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
        ) = GetReferenceExchangeRateUseCase(repository, port, clock)

        fun published(
            baseDate: String,
            rate: String = "9.0909",
        ) = PublishedExchangeRate(LocalDate.parse(baseDate), BigDecimal(rate))

        test("결제일의 행이 저장되어 있으면 고시 기관을 호출하지 않고 저장한 값을 반환한다") {
            // given
            val repository = FakeRepository()
            repository.saveIfAbsent("ECB", "JPY", LocalDate.parse("2026-11-07"), LocalDate.parse("2026-11-06"), BigDecimal("9.126"), now)
            val port = FakePort(published("2026-11-07", "1.00"))

            // when
            val result = useCase(repository, port).get("JPY", LocalDate.parse("2026-11-07"))

            // then
            assertSoftly {
                port.calls shouldBe 0
                result.rate shouldBe BigDecimal("9.13")
                result.baseDate shouldBe LocalDate.parse("2026-11-06")
                result.provider shouldBe "ECB"
            }
        }

        test("고시 기관이 9.0909를 반환하면 9.0909를 저장하고 응답의 환율은 9.09로 반환한다") {
            // given
            val repository = FakeRepository()
            val port = FakePort(published("2026-11-06"))

            // when
            val result = useCase(repository, port).get("JPY", LocalDate.parse("2026-11-06"))

            // then
            assertSoftly {
                repository.rows.single().rate shouldBe BigDecimal("9.0909")
                result.rate shouldBe BigDecimal("9.09")
                result.currency shouldBe "JPY"
            }
        }

        test("응답의 환율은 소수점 셋째 자리에서 HALF_UP으로 반올림한다") {
            // given
            val port = FakePort(published("2026-11-06", "9.125"))

            // when
            val result = useCase(FakeRepository(), port).get("JPY", LocalDate.parse("2026-11-06"))

            // then
            result.rate shouldBe BigDecimal("9.13")
        }

        test("기준일이 결제일과 같으면 결제일이 서버 날짜의 전날이어도 행을 저장한다") {
            // given
            val repository = FakeRepository()
            val port = FakePort(published("2026-11-08"))

            // when
            useCase(repository, port).get("JPY", LocalDate.parse("2026-11-08"))

            // then
            repository.rows.single().let {
                it.rateDate shouldBe LocalDate.parse("2026-11-08")
                it.baseDate shouldBe LocalDate.parse("2026-11-08")
            }
        }

        test("결제일이 서버 날짜보다 2일 이전이고 기준일이 결제일과 다르면 기준일과 결제일을 각각 담아 행을 저장한다") {
            // given
            val repository = FakeRepository()
            val port = FakePort(published("2026-11-06"))

            // when
            val result = useCase(repository, port).get("JPY", LocalDate.parse("2026-11-07"))

            // then
            assertSoftly {
                repository.rows.single().rateDate shouldBe LocalDate.parse("2026-11-07")
                repository.rows.single().baseDate shouldBe LocalDate.parse("2026-11-06")
                repository.rows.single().createdAt shouldBe now
                result.baseDate shouldBe LocalDate.parse("2026-11-06")
            }
        }

        test("결제일이 서버 날짜보다 2일 미만 이전이고 기준일이 결제일과 다르면 행을 저장하지 않고 응답에만 쓴다") {
            // given
            val repository = FakeRepository()
            val port = FakePort(published("2026-11-06"))

            // when
            val result = useCase(repository, port).get("JPY", LocalDate.parse("2026-11-08"))

            // then
            assertSoftly {
                repository.rows.size shouldBe 0
                result.baseDate shouldBe LocalDate.parse("2026-11-06")
            }
        }

        test("결제일이 서버 날짜인데 기준일이 이전이면 행을 저장하지 않는다") {
            // given
            val repository = FakeRepository()
            val port = FakePort(published("2026-11-06"))

            // when
            useCase(repository, port).get("JPY", today)

            // then
            repository.rows.size shouldBe 0
        }

        test("기준일이 결제일보다 7일 이전이면 환율을 반환한다") {
            // given
            val port = FakePort(published("2026-10-30"))

            // when
            val result = useCase(FakeRepository(), port).get("JPY", LocalDate.parse("2026-11-06"))

            // then
            result.baseDate shouldBe LocalDate.parse("2026-10-30")
        }

        test("기준일이 결제일보다 7일 넘게 이전이면 404를 반환하고 행을 저장하지 않는다") {
            // given
            val repository = FakeRepository()
            val port = FakePort(published("2026-10-29"))

            // when
            val exception = shouldThrow<BusinessException> { useCase(repository, port).get("JPY", LocalDate.parse("2026-11-06")) }

            // then
            assertSoftly {
                exception.errorCode shouldBe ExchangeRateErrorCode.NOT_PUBLISHED
                repository.rows.size shouldBe 0
            }
        }

        test("고시 기관에 고시값이 없으면 404를 반환하고 행을 저장하지 않는다") {
            // given
            val repository = FakeRepository()

            // when
            val exception = shouldThrow<BusinessException> { useCase(repository, FakePort(null)).get("JPY", LocalDate.parse("1998-12-31")) }

            // then
            assertSoftly {
                exception.errorCode shouldBe ExchangeRateErrorCode.NOT_PUBLISHED
                repository.rows.size shouldBe 0
            }
        }

        test("고시 기관을 조회하지 못하면 503을 그대로 전달하고 행을 저장하지 않는다") {
            // given
            val repository = FakeRepository()
            val port = FakePort(null, BusinessException(ExchangeRateErrorCode.PROVIDER_UNAVAILABLE))

            // when
            val exception = shouldThrow<BusinessException> { useCase(repository, port).get("JPY", LocalDate.parse("2026-11-06")) }

            // then
            assertSoftly {
                exception.errorCode shouldBe ExchangeRateErrorCode.PROVIDER_UNAVAILABLE
                repository.rows.size shouldBe 0
            }
        }

        test("서버 날짜의 다음 날인 결제일은 고시 기관을 호출하지 않고 400으로 거절한다") {
            // given
            val port = FakePort(published("2026-11-09"))

            // when
            val exception = shouldThrow<BusinessException> { useCase(FakeRepository(), port).get("JPY", today.plusDays(1)) }

            // then
            assertSoftly {
                exception.errorCode shouldBe CommonErrorCode.INVALID_REQUEST
                port.calls shouldBe 0
            }
        }

        test("서버 날짜(Asia/Seoul)와 같은 결제일은 UTC로는 전날이어도 거절하지 않는다") {
            // given
            val port = FakePort(published("2026-11-06"))

            // when
            val result = useCase(FakeRepository(), port).get("JPY", today)

            // then
            result.baseDate shouldBe LocalDate.parse("2026-11-06")
        }

        // UTC 2026-11-08T10:30은 서울 2026-11-08 19:30이고 UTC+14 2026-11-09 00:30입니다.
        val beforeSeoulMidnight = Clock.fixed(Instant.parse("2026-11-08T10:30:00Z"), ZoneOffset.UTC)

        test("서울 날짜로는 다음 날이어도 UTC+14의 현재 날짜와 같은 결제일은 환율을 조회한다") {
            // given
            val port = FakePort(published("2026-11-06"))

            // when
            val result = useCase(FakeRepository(), port, beforeSeoulMidnight).get("JPY", LocalDate.parse("2026-11-09"))

            // then
            assertSoftly {
                result.baseDate shouldBe LocalDate.parse("2026-11-06")
                port.calls shouldBe 1
            }
        }

        test("UTC+14의 현재 날짜 다음 날인 결제일은 고시 기관을 호출하지 않고 400으로 거절한다") {
            // given
            val port = FakePort(published("2026-11-06"))

            // when
            val exception =
                shouldThrow<BusinessException> {
                    useCase(FakeRepository(), port, beforeSeoulMidnight).get("JPY", LocalDate.parse("2026-11-10"))
                }

            // then
            assertSoftly {
                exception.errorCode shouldBe CommonErrorCode.INVALID_REQUEST
                port.calls shouldBe 0
            }
        }
    })
