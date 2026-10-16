package com.ject.external.exchangerate.frankfurter

import com.ject.external.exchangerate.ExchangeRateException
import com.ject.external.exchangerate.ExchangeRateFailureReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class FrankfurterExchangeRateClientTest :
    FunSpec({
        lateinit var server: MockRestServiceServer
        lateinit var client: FrankfurterExchangeRateClient
        val date = LocalDate.parse("2026-10-18")

        beforeTest {
            val builder = RestClient.builder().baseUrl("https://api.frankfurter.dev")
            server = MockRestServiceServer.bindTo(builder).build()
            client =
                FrankfurterExchangeRateClient(
                    builder.build(),
                    "ECB",
                    Clock.fixed(Instant.parse("2026-10-17T15:00:00Z"), ZoneOffset.UTC),
                )
        }

        afterTest { server.verify() }

        test("한국 시각 자정에 오늘 날짜로 조회하고 이전 환율 기준일과 원본 정밀도를 보존한다") {
            // given
            server
                .expect(requestTo("https://api.frankfurter.dev/v2/providers/ecb/rates?base=EUR&quotes=JPY,KRW,USD&date=2026-10-18"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                    withSuccess(
                        """[
                {"date":"2026-10-16","base":"EUR","quote":"USD","rate":1.12531234567890123456789},
                {"date":"2026-10-15","base":"EUR","quote":"KRW","rate":1499.01},
                {"date":"2026-10-16","base":"EUR","quote":"JPY","rate":177.82}
            ]""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            // when
            val result = client.fetch("EUR", setOf("USD", "JPY", "KRW"), date)

            // then
            result.map { it.quote } shouldBe listOf("KRW", "JPY", "USD")
            result.map { it.date } shouldBe
                listOf(
                    LocalDate.parse("2026-10-15"),
                    LocalDate.parse("2026-10-16"),
                    LocalDate.parse("2026-10-16"),
                )
            result.last().rate shouldBe BigDecimal("1.12531234567890123456789")
            result.map { it.source }.toSet() shouldBe setOf("ECB")
        }

        test("미래 날짜는 외부 호출 전에 거절한다") {
            // given
            val futureDate = date.plusDays(1)

            // when
            val failure = shouldThrow<IllegalArgumentException> { client.fetch("EUR", setOf("KRW"), futureDate) }

            // then
            failure.message shouldBe "환율 조회 날짜는 오늘 이후일 수 없습니다."
        }

        test("통화 코드가 잘못되거나 환산할 통화가 없으면 외부 호출 전에 거절한다") {
            // given
            val invalidCodes = listOf("krw", "KR", "ZZZ", "KRW,USD")

            // when / then
            for (code in invalidCodes) {
                shouldThrow<IllegalArgumentException> { client.fetch(code, setOf("USD"), date) }
                shouldThrow<IllegalArgumentException> { client.fetch("EUR", setOf(code), date) }
            }
            shouldThrow<IllegalArgumentException> { client.fetch("EUR", emptySet(), date) }
        }

        test("운영 설정의 환율 제공 기관과 호출자가 선택한 기준 통화를 사용한다") {
            // given
            val builder = RestClient.builder().baseUrl("https://api.frankfurter.dev")
            val otherServer = MockRestServiceServer.bindTo(builder).build()
            val otherClient =
                FrankfurterExchangeRateClient(
                    builder.build(),
                    "BOC",
                    Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC),
                )
            otherServer
                .expect(requestTo("https://api.frankfurter.dev/v2/providers/boc/rates?base=KRW&quotes=USD&date=2026-10-18"))
                .andRespond(
                    withSuccess(
                        """[{"date":"2026-10-16","base":"KRW","quote":"USD","rate":0.00075}]""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            // when
            val result = otherClient.fetch("KRW", setOf("USD"), date)

            // then
            result.single().base shouldBe "KRW"
            result.single().source shouldBe "BOC"
            result.single().rate shouldBe BigDecimal("0.00075")
            otherServer.verify()
        }

        test("정상적인 빈 목록과 일부 통화 누락은 그대로 반환한다") {
            // given
            server
                .expect(requestTo("https://api.frankfurter.dev/v2/providers/ecb/rates?base=EUR&quotes=KRW,USD&date=2026-10-18"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))
            server
                .expect(requestTo("https://api.frankfurter.dev/v2/providers/ecb/rates?base=EUR&quotes=KRW,USD&date=2026-10-18"))
                .andRespond(
                    withSuccess(
                        """[{"date":"2026-10-16","base":"EUR","quote":"USD","rate":1.12}]""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            // when
            val empty = client.fetch("EUR", setOf("KRW", "USD"), date)
            val partial = client.fetch("EUR", setOf("KRW", "USD"), date)

            // then
            empty shouldBe emptyList()
            partial.map { it.quote } shouldBe listOf("USD")
        }

        test("기준 통화와 환산할 통화가 같으면 환율 1을 보존한다") {
            // given
            server
                .expect(requestTo("https://api.frankfurter.dev/v2/providers/ecb/rates?base=EUR&quotes=EUR&date=2026-10-18"))
                .andRespond(
                    withSuccess(
                        """[{"date":"2026-10-16","base":"EUR","quote":"EUR","rate":1}]""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            // when
            val result = client.fetch("EUR", setOf("EUR"), date)

            // then
            result.single().rate shouldBe BigDecimal.ONE
        }

        val invalidBodies =
            listOf(
                "",
                "null",
                "[null]",
                "{}",
                "not-json",
                """[{"date":"2026-10-16","base":"EUR","quote":"KRW"}]""",
                """[{"date":null,"base":"EUR","quote":"KRW","rate":1}]""",
                """[{"date":"invalid","base":"EUR","quote":"KRW","rate":1}]""",
                """[{"date":"2026-10-19","base":"EUR","quote":"KRW","rate":1}]""",
                """[{"date":"2026-10-16","base":"USD","quote":"KRW","rate":1}]""",
                """[{"date":"2026-10-16","base":"EUR","quote":"USD","rate":1}]""",
                """[{"date":"2026-10-16","base":"EUR","quote":"KRW","rate":0}]""",
                """[{"date":"2026-10-16","base":"EUR","quote":"KRW","rate":-1}]""",
                """[
            {"date":"2026-10-16","base":"EUR","quote":"KRW","rate":1},
            {"date":"2026-10-15","base":"EUR","quote":"KRW","rate":2}
        ]""",
            )
        for ((index, body) in invalidBodies.withIndex()) {
            test("잘못된 외부 응답을 거절한다: 사례 $index") {
                // given
                server
                    .expect(requestTo("https://api.frankfurter.dev/v2/providers/ecb/rates?base=EUR&quotes=KRW&date=2026-10-18"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

                // when
                val failure = shouldThrow<ExchangeRateException> { client.fetch("EUR", setOf("KRW"), date) }

                // then
                failure.reason shouldBe ExchangeRateFailureReason.INVALID_RESPONSE
            }
        }

        test("기준 통화가 요청과 다르면 예외 메시지에 실패 사유를 남긴다") {
            // given
            server
                .expect(requestTo("https://api.frankfurter.dev/v2/providers/ecb/rates?base=EUR&quotes=KRW&date=2026-10-18"))
                .andRespond(
                    withSuccess(
                        """[{"date":"2026-10-16","base":"USD","quote":"KRW","rate":1}]""",
                        MediaType.APPLICATION_JSON,
                    ),
                )

            // when
            val failure = shouldThrow<ExchangeRateException> { client.fetch("EUR", setOf("KRW"), date) }

            // then
            failure.reason shouldBe ExchangeRateFailureReason.INVALID_RESPONSE
            failure.message shouldBe "응답의 기준 통화가 요청과 다릅니다."
        }

        for (status in listOf(
            HttpStatus.NOT_FOUND,
            HttpStatus.UNPROCESSABLE_ENTITY,
            HttpStatus.TOO_MANY_REQUESTS,
            HttpStatus.SERVICE_UNAVAILABLE,
        )) {
            test("HTTP 오류는 상태 코드를 보존하고 빈 조회 결과로 숨기지 않는다: $status") {
                // given
                server
                    .expect(requestTo("https://api.frankfurter.dev/v2/providers/ecb/rates?base=EUR&quotes=KRW&date=2026-10-18"))
                    .andRespond(withStatus(status))

                // when
                val failure = shouldThrow<ExchangeRateException> { client.fetch("EUR", setOf("KRW"), date) }

                // then
                failure.reason shouldBe ExchangeRateFailureReason.HTTP_ERROR
                failure.statusCode shouldBe status.value()
            }
        }

        for ((original, reason) in listOf(
            ConnectException("connection refused") to ExchangeRateFailureReason.CONNECTION,
            SocketTimeoutException("timed out") to ExchangeRateFailureReason.TIMEOUT,
        )) {
            test("네트워크 실패는 원인과 실패 종류를 보존한다: $reason") {
                // given
                server
                    .expect(requestTo("https://api.frankfurter.dev/v2/providers/ecb/rates?base=EUR&quotes=KRW&date=2026-10-18"))
                    .andRespond { throw original }

                // when
                val failure = shouldThrow<ExchangeRateException> { client.fetch("EUR", setOf("KRW"), date) }

                // then
                failure.reason shouldBe reason
                failure.cause?.cause shouldBe original
            }
        }
    })
