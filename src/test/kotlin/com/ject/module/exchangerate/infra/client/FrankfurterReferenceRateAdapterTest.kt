package com.ject.module.exchangerate.infra.client

import com.ject.external.exchangerate.ExchangeRateClient
import com.ject.external.exchangerate.ExchangeRateData
import com.ject.external.exchangerate.ExchangeRateException
import com.ject.external.exchangerate.ExchangeRateFailureReason
import com.ject.external.exchangerate.frankfurter.FrankfurterProperties
import com.ject.global.error.BusinessException
import com.ject.module.exchangerate.domain.ExchangeRateErrorCode
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.LocalDate

class FrankfurterReferenceRateAdapterTest :
    FunSpec({
        val date = LocalDate.parse("2026-11-07")

        fun adapter(client: ExchangeRateClient) = FrankfurterReferenceRateAdapter(client, FrankfurterProperties("ECB"))

        test("결제 통화를 기준 통화로, KRW를 대상 통화로 조회해 실제 기준일과 환율을 반환한다") {
            // given
            var requested: Triple<String, Set<String>, LocalDate>? = null
            val client =
                object : ExchangeRateClient {
                    override fun fetch(
                        base: String,
                        quotes: Set<String>,
                        date: LocalDate,
                    ): List<ExchangeRateData> {
                        requested = Triple(base, quotes, date)
                        return listOf(ExchangeRateData(LocalDate.parse("2026-11-06"), base, "KRW", BigDecimal("8.5819"), "ECB"))
                    }
                }

            // when
            val result = adapter(client).fetch("JPY", date)

            // then
            assertSoftly {
                requested shouldBe Triple("JPY", setOf("KRW"), date)
                result?.baseDate shouldBe LocalDate.parse("2026-11-06")
                result?.rate shouldBe BigDecimal("8.5819")
            }
        }

        test("고시 기관이 빈 목록을 주면 고시값 없음으로 반환한다") {
            // given
            val client =
                object : ExchangeRateClient {
                    override fun fetch(
                        base: String,
                        quotes: Set<String>,
                        date: LocalDate,
                    ): List<ExchangeRateData> = emptyList()
                }

            // when
            val result = adapter(client).fetch("JPY", date)

            // then
            result.shouldBeNull()
        }

        ExchangeRateFailureReason.entries.forEach { reason ->
            test("외부 조회가 $reason 로 실패하면 고시값 없음이 아니라 조회 불가 503으로 바꾼다") {
                // given
                val client =
                    object : ExchangeRateClient {
                        override fun fetch(
                            base: String,
                            quotes: Set<String>,
                            date: LocalDate,
                        ): List<ExchangeRateData> = throw ExchangeRateException(reason)
                    }

                // when
                val exception = shouldThrow<BusinessException> { adapter(client).fetch("JPY", date) }

                // then
                exception.errorCode shouldBe ExchangeRateErrorCode.PROVIDER_UNAVAILABLE
            }
        }

        test("제공자 코드는 설정한 환율 제공 기관이다") {
            // given
            val client =
                object : ExchangeRateClient {
                    override fun fetch(
                        base: String,
                        quotes: Set<String>,
                        date: LocalDate,
                    ): List<ExchangeRateData> = emptyList()
                }

            // when
            val provider = adapter(client).provider

            // then
            provider shouldBe "ECB"
        }
    })
