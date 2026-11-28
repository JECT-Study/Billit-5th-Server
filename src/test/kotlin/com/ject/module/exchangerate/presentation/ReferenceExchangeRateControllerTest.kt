package com.ject.module.exchangerate.presentation

import com.ject.global.error.BusinessException
import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.module.exchangerate.domain.ExchangeRateErrorCode
import com.ject.module.exchangerate.domain.PublishedExchangeRate
import com.ject.module.exchangerate.usecase.port.ReferenceRateProviderPort
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.ZoneId

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReferenceExchangeRateControllerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    @MockitoBean
    private lateinit var referenceRateProviderPort: ReferenceRateProviderPort

    private val httpClient = HttpClient.newHttpClient()
    private val memberIds = mutableListOf<Long>()
    private val provider = "ECB"
    private val today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))

    init {
        beforeEach {
            doReturn(provider).`when`(referenceRateProviderPort).provider
        }

        afterEach {
            jdbc.update("DELETE FROM reference_exchange_rate WHERE provider = ?", provider)
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        context("GET /api/v1/reference-exchange-rates") {
            test("결제일에 고시된 환율을 200으로 반환하고 환율은 소수점 셋째 자리에서 반올림한 2자리 문자열이다") {
                // given
                val paidDate = today.minusDays(10)
                doReturn(PublishedExchangeRate(paidDate, BigDecimal("9.0909"))).`when`(referenceRateProviderPort).fetch("JPY", paidDate)

                // when
                val response = get("JPY", paidDate.toString(), accessToken())

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe
                        mapOf(
                            "data" to mapOf("currency" to "JPY", "rate" to "9.09", "provider" to "ECB", "baseDate" to paidDate.toString()),
                        )
                }
            }

            test("결제일에 고시된 환율이 없으면 이전 고시값과 실제 기준일을 반환한다") {
                // given
                val paidDate = today.minusDays(10)
                val baseDate = paidDate.minusDays(2)
                doReturn(PublishedExchangeRate(baseDate, BigDecimal("1358.73"))).`when`(referenceRateProviderPort).fetch("USD", paidDate)

                // when
                val response = get("USD", paidDate.toString(), accessToken())

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    (body(response)["data"] as Map<*, *>)["baseDate"] shouldBe baseDate.toString()
                    (body(response)["data"] as Map<*, *>)["rate"] shouldBe "1358.73"
                }
            }

            test("저장 조건을 만족하는 환율은 같은 통화와 결제일의 다음 요청에서 고시 기관을 호출하지 않고 같은 값을 반환한다") {
                // given
                val paidDate = today.minusDays(10)
                doReturn(
                    PublishedExchangeRate(paidDate.minusDays(1), BigDecimal("9.0909")),
                ).`when`(referenceRateProviderPort).fetch("JPY", paidDate)
                val token = accessToken()

                // when
                val first = get("JPY", paidDate.toString(), token)
                val second = get("JPY", paidDate.toString(), token)

                // then
                assertSoftly {
                    first.statusCode() shouldBe 200
                    second.body() shouldBe first.body()
                    fetchCount() shouldBe 1
                    jdbc.queryForObject("SELECT count(*) FROM reference_exchange_rate WHERE provider = 'ECB'", Long::class.java) shouldBe 1L
                }
            }

            test("서버 날짜에 가까운 결제일의 이전 고시값은 저장하지 않아 다음 요청에서 다시 고시 기관을 호출한다") {
                // given
                val paidDate = today.minusDays(1)
                doReturn(
                    PublishedExchangeRate(paidDate.minusDays(2), BigDecimal("9.0909")),
                ).`when`(referenceRateProviderPort).fetch("JPY", paidDate)
                val token = accessToken()

                // when
                get("JPY", paidDate.toString(), token)
                val second = get("JPY", paidDate.toString(), token)

                // then
                assertSoftly {
                    second.statusCode() shouldBe 200
                    fetchCount() shouldBe 2
                    jdbc.queryForObject("SELECT count(*) FROM reference_exchange_rate WHERE provider = 'ECB'", Long::class.java) shouldBe 0L
                }
            }

            test("결제일과 그 이전 7일 안에 고시값이 없으면 404 EXCHANGE_RATE_NOT_PUBLISHED를 반환하고 저장하지 않는다") {
                // given
                val paidDate = today.minusDays(10)
                doReturn(
                    PublishedExchangeRate(paidDate.minusDays(8), BigDecimal("9.0909")),
                ).`when`(referenceRateProviderPort).fetch("JPY", paidDate)

                // when
                val response = get("JPY", paidDate.toString(), accessToken())

                // then
                assertSoftly {
                    response.statusCode() shouldBe 404
                    body(response)["code"] shouldBe "EXCHANGE_RATE_NOT_PUBLISHED"
                    jdbc.queryForObject("SELECT count(*) FROM reference_exchange_rate WHERE provider = 'ECB'", Long::class.java) shouldBe 0L
                }
            }

            test("고시 기관의 장애는 503 EXCHANGE_RATE_PROVIDER_UNAVAILABLE을 반환하고 저장하지 않는다") {
                // given
                val paidDate = today.minusDays(10)
                doThrow(
                    BusinessException(ExchangeRateErrorCode.PROVIDER_UNAVAILABLE),
                ).`when`(referenceRateProviderPort).fetch("JPY", paidDate)

                // when
                val response = get("JPY", paidDate.toString(), accessToken())

                // then
                assertSoftly {
                    response.statusCode() shouldBe 503
                    body(response)["code"] shouldBe "EXCHANGE_RATE_PROVIDER_UNAVAILABLE"
                    jdbc.queryForObject("SELECT count(*) FROM reference_exchange_rate WHERE provider = 'ECB'", Long::class.java) shouldBe 0L
                }
            }

            test("currency가 KRW이면 고시 기관을 호출하지 않고 400 COMMON_INVALID_REQUEST를 반환한다") {
                // when
                val response = get("KRW", today.minusDays(10).toString(), accessToken())

                // then
                assertSoftly {
                    response.statusCode() shouldBe 400
                    body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                    body(response)["errors"] shouldBe listOf(mapOf("field" to "currency", "reason" to "허용되지 않는 값입니다."))
                    fetchCount() shouldBe 0
                }
            }

            test("미래 날짜의 결제일은 400 COMMON_INVALID_REQUEST를 반환하고 고시 기관을 호출하지 않는다") {
                // when
                val response = get("JPY", today.plusDays(1).toString(), accessToken())

                // then
                assertSoftly {
                    response.statusCode() shouldBe 400
                    body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                    fetchCount() shouldBe 0
                }
            }

            listOf(
                "지원하지 않는 통화" to "currency=GBP&paidDate=2026-11-05",
                "소문자 통화" to "currency=jpy&paidDate=2026-11-05",
                "통화 누락" to "paidDate=2026-11-05",
                "결제일 누락" to "currency=JPY",
                "자릿수가 모자란 결제일" to "currency=JPY&paidDate=2026-1-5",
                "없는 날짜인 결제일" to "currency=JPY&paidDate=2026-02-30",
            ).forEach { (name, query) ->
                test("$name 요청은 400 COMMON_INVALID_REQUEST를 반환한다") {
                    // when
                    val response = request(query, accessToken())

                    // then
                    assertSoftly {
                        response.statusCode() shouldBe 400
                        body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                    }
                }
            }

            test("인증 정보가 없으면 401을 반환한다") {
                // when
                val response = request("currency=JPY&paidDate=${today.minusDays(10)}", null)

                // then
                response.statusCode() shouldBe 401
            }

            test("가입 전용 토큰으로는 요청할 수 없다") {
                // given
                val registrationToken = authTokenCreator.createRegistrationToken(createMember())

                // when
                val response = request("currency=JPY&paidDate=${today.minusDays(10)}", registrationToken)

                // then
                assertSoftly {
                    (response.statusCode() in listOf(401, 403)) shouldBe true
                    fetchCount() shouldBe 0
                }
            }
        }
    }

    private fun fetchCount(): Int = mockingDetails(referenceRateProviderPort).invocations.count { it.method.name == "fetch" }

    private fun createMember(): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(id)
        return id
    }

    private fun accessToken(): String = authTokenCreator.createMemberTokens(createMember()).accessToken

    private fun get(
        currency: String,
        paidDate: String,
        token: String?,
    ): HttpResponse<String> = request("currency=$currency&paidDate=$paidDate", token)

    private fun request(
        query: String,
        token: String?,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(
                    URI.create(
                        "http://localhost:${environment.getRequiredProperty("local.server.port")}/api/v1/reference-exchange-rates?$query",
                    ),
                ).GET()
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun body(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)
}
