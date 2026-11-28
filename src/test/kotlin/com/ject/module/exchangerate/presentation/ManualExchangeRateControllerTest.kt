package com.ject.module.exchangerate.presentation

import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManualExchangeRateControllerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    private val httpClient = HttpClient.newHttpClient()
    private val memberIds = mutableListOf<Long>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM manual_exchange_rate WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        context("POST /api/v1/trips/{tripId}/exchange-rates") {
            test("등록하면 201과 등록한 기록을 반환하고 환율은 소수점 이하 2자리 문자열이다") {
                // given
                val trip = createTrip()

                // when
                val response = post(trip.tripId, trip.token, VALID_BODY)

                // then
                val data = body(response)["data"] as Map<*, *>
                assertSoftly {
                    response.statusCode() shouldBe 201
                    data["exchangeRateId"] shouldBe
                        jdbc
                            .queryForObject(
                                "SELECT id FROM manual_exchange_rate WHERE trip_id = ?",
                                Long::class.java,
                                trip.tripId,
                            ).toString()
                    data["currency"] shouldBe "JPY"
                    data["rate"] shouldBe "9.12"
                    data["exchangeMethod"] shouldBe "CASH"
                    data["exchangeDate"] shouldBe "2026-11-05"
                    (data["createdAt"] as String).endsWith("Z") shouldBe true
                }
            }

            test("같은 기록을 다시 등록하면 409 EXCHANGE_RATE_DUPLICATED를 반환한다") {
                // given
                val trip = createTrip()
                post(trip.tripId, trip.token, VALID_BODY)

                // when
                val response = post(trip.tripId, trip.token, VALID_BODY)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    body(response)["code"] shouldBe "EXCHANGE_RATE_DUPLICATED"
                }
            }

            listOf(
                "KRW 통화" to VALID_BODY.replace("JPY", "KRW"),
                "지원하지 않는 통화" to VALID_BODY.replace("JPY", "GBP"),
                "소문자 통화" to VALID_BODY.replace("JPY", "jpy"),
                "0 환율" to VALID_BODY.replace("9.12", "0.00"),
                "소수점 첫째 자리까지만 있는 환율" to VALID_BODY.replace("9.12", "9.1"),
                "소수점 셋째 자리 환율" to VALID_BODY.replace("9.12", "9.123"),
                "음수 환율" to VALID_BODY.replace("9.12", "-9.12"),
                "정수부 11자리 환율" to VALID_BODY.replace("9.12", "12345678901.00"),
                "지원하지 않는 환전 수단" to VALID_BODY.replace("CASH", "CARD"),
                "자릿수가 모자란 환전 날짜" to VALID_BODY.replace("2026-11-05", "2026-1-5"),
                "없는 날짜인 환전 날짜" to VALID_BODY.replace("2026-11-05", "2026-02-30"),
                "환율 누락" to """{"currency":"JPY","exchangeMethod":"CASH","exchangeDate":"2026-11-05"}""",
                "정의하지 않은 필드" to VALID_BODY.replace("}", ""","extra":"x"}"""),
            ).forEach { (name, requestBody) ->
                test("$name 요청은 400 COMMON_INVALID_REQUEST를 반환하고 저장하지 않는다") {
                    // given
                    val trip = createTrip()

                    // when
                    val response = post(trip.tripId, trip.token, requestBody)

                    // then
                    assertSoftly {
                        response.statusCode() shouldBe 400
                        body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                        countRecords(trip.tripId) shouldBe 0
                    }
                }
            }

            test("여행 참여자가 아니면 403을 반환한다") {
                // given
                val trip = createTrip()

                // when
                val response = post(trip.tripId, accessToken(createMember()), VALID_BODY)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 403
                    body(response)["code"] shouldBe "COMMON_FORBIDDEN"
                }
            }

            test("없는 여행이면 404를 반환한다") {
                // given
                val trip = createTrip()

                // when
                val response = post(Long.MAX_VALUE, trip.token, VALID_BODY)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 404
                    body(response)["code"] shouldBe "COMMON_NOT_FOUND"
                }
            }

            test("인증 정보가 없으면 401을 반환한다") {
                // given
                val trip = createTrip()

                // when
                val response = post(trip.tripId, null, VALID_BODY)

                // then
                response.statusCode() shouldBe 401
            }
        }

        context("GET /api/v1/trips/{tripId}/exchange-rates") {
            test("본인이 등록한 기록만 최신 등록순으로 반환하고 page 정보를 담는다") {
                // given
                val trip = createTrip()
                val other = createMember()
                addTripMember(trip.tripId, other, "민지", "ADMIN")
                post(trip.tripId, trip.token, VALID_BODY)
                post(trip.tripId, trip.token, VALID_BODY.replace("2026-11-05", "2026-11-06"))
                post(trip.tripId, accessToken(other), VALID_BODY.replace("9.12", "9.50"))

                // when
                val response = get(trip.tripId, trip.token, "")

                // then
                val data = body(response)["data"] as List<*>
                assertSoftly {
                    response.statusCode() shouldBe 200
                    data.map { (it as Map<*, *>)["exchangeDate"] } shouldBe listOf("2026-11-06", "2026-11-05")
                    data.map { (it as Map<*, *>)["rate"] } shouldBe listOf("9.12", "9.12")
                    body(response)["page"] shouldBe mapOf("page" to 0, "size" to 20, "totalElements" to 2, "hasNext" to false)
                }
            }

            test("기록이 없으면 빈 목록을 반환한다") {
                // given
                val trip = createTrip()

                // when
                val response = get(trip.tripId, trip.token, "")

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response)["data"] shouldBe emptyList<Any>()
                }
            }

            listOf("page=-1", "size=0", "size=101", "page=a").forEach { query ->
                test("$query 요청은 400 COMMON_INVALID_REQUEST를 반환한다") {
                    // given
                    val trip = createTrip()

                    // when
                    val response = get(trip.tripId, trip.token, query)

                    // then
                    assertSoftly {
                        response.statusCode() shouldBe 400
                        body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                    }
                }
            }

            test("여행 참여자가 아니면 403을 반환한다") {
                // given
                val trip = createTrip()

                // when
                val response = get(trip.tripId, accessToken(createMember()), "")

                // then
                response.statusCode() shouldBe 403
            }

            test("없는 여행이면 404를 반환한다") {
                // given
                val trip = createTrip()

                // when
                val response = get(Long.MAX_VALUE, trip.token, "")

                // then
                response.statusCode() shouldBe 404
            }

            test("인증 정보가 없으면 401을 반환한다") {
                // given
                val trip = createTrip()

                // when
                val response = get(trip.tripId, null, "")

                // then
                response.statusCode() shouldBe 401
            }
        }

        context("DELETE /api/v1/trips/{tripId}/exchange-rates/{exchangeRateId}") {
            test("본인이 등록한 기록을 삭제하면 204를 반환하고 목록에서 빠진다") {
                // given
                val trip = createTrip()
                val rateId = insertRate(trip.tripId, trip.tripMemberId)

                // when
                val response = delete(trip.tripId, rateId.toString(), trip.token)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 204
                    response.body() shouldBe ""
                    countRecords(trip.tripId) shouldBe 0
                }
            }

            test("이미 삭제한 기록과 다른 참여자의 기록은 404 COMMON_NOT_FOUND를 반환한다") {
                // given
                val trip = createTrip()
                val otherMemberId = createMember()
                val otherTripMemberId = addTripMember(trip.tripId, otherMemberId, "민지", "MEMBER")
                val othersRate = insertRate(trip.tripId, otherTripMemberId)
                val deleted = insertRate(trip.tripId, trip.tripMemberId)
                delete(trip.tripId, deleted.toString(), trip.token)

                // when
                val forOthers = delete(trip.tripId, othersRate.toString(), trip.token)
                val again = delete(trip.tripId, deleted.toString(), trip.token)

                // then
                assertSoftly {
                    forOthers.statusCode() shouldBe 404
                    body(forOthers)["code"] shouldBe "COMMON_NOT_FOUND"
                    again.statusCode() shouldBe 404
                    body(again)["code"] shouldBe "COMMON_NOT_FOUND"
                    countRecords(trip.tripId) shouldBe 1
                }
            }

            test("숫자가 아닌 기록 ID는 400 COMMON_INVALID_REQUEST를 반환한다") {
                // given
                val trip = createTrip()

                // when
                val response = delete(trip.tripId, "abc", trip.token)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 400
                    body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                }
            }

            test("여행 참여자가 아니면 403을 반환한다") {
                // given
                val trip = createTrip()
                val rateId = insertRate(trip.tripId, trip.tripMemberId)

                // when
                val response = delete(trip.tripId, rateId.toString(), accessToken(createMember()))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 403
                    body(response)["code"] shouldBe "COMMON_FORBIDDEN"
                    countRecords(trip.tripId) shouldBe 1
                }
            }

            test("인증 정보가 없으면 401을 반환한다") {
                // given
                val trip = createTrip()

                // when
                val response = delete(trip.tripId, "1", null)

                // then
                response.statusCode() shouldBe 401
            }
        }
    }

    private fun countRecords(tripId: Long): Long =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM manual_exchange_rate WHERE trip_id = ?", Long::class.java, tripId))

    private fun createTrip(): TripFixture {
        val key = UUID.randomUUID()
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, invite_code, " +
                        "idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', '2026-11-05', '2026-11-07', 'JP', 'JPY', 4, 'CONFIRMED', ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    key.toString().replace("-", "").take(20),
                    key,
                ),
            )
        tripIds.add(tripId)
        val memberId = createMember()
        val tripMemberId = addTripMember(tripId, memberId, "지훈", "MEMBER")
        return TripFixture(tripId, accessToken(memberId), tripMemberId)
    }

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

    private fun addTripMember(
        tripId: Long,
        memberId: Long,
        tripNickname: String,
        role: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?::trip_member_role, 'MEMBER'::trip_member_status, now(), now()) RETURNING id",
                Long::class.java,
                tripId,
                memberId,
                tripNickname,
                role,
            ),
        )

    private fun insertRate(
        tripId: Long,
        tripMemberId: Long,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO manual_exchange_rate (trip_id, currency, rate, exchange_date, exchange_method, created_by_trip_member_id, " +
                    "created_at, updated_at) VALUES (?, 'JPY', 9.12, '2026-11-05'::date, 'CASH'::exchange_method, ?, now(), now()) RETURNING id",
                Long::class.java,
                tripId,
                tripMemberId,
            ),
        )

    private fun delete(
        tripId: Long,
        exchangeRateId: String,
        token: String?,
    ): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(URI.create("${baseUrl()}/api/v1/trips/$tripId/exchange-rates/$exchangeRateId")).DELETE()
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun accessToken(memberId: Long): String = authTokenCreator.createMemberTokens(memberId).accessToken

    private fun post(
        tripId: Long,
        token: String?,
        requestBody: String,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("${baseUrl()}/api/v1/trips/$tripId/exchange-rates"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(
        tripId: Long,
        token: String?,
        query: String,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("${baseUrl()}/api/v1/trips/$tripId/exchange-rates?$query")).GET()
        if (token != null) {
            builder.header("Authorization", "Bearer $token")
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun baseUrl() = "http://localhost:${environment.getRequiredProperty("local.server.port")}"

    private fun body(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)

    private data class TripFixture(
        val tripId: Long,
        val token: String,
        val tripMemberId: Long,
    )

    companion object {
        private const val VALID_BODY = """{"currency":"JPY","rate":"9.12","exchangeMethod":"CASH","exchangeDate":"2026-11-05"}"""
    }
}
