package com.ject.module.expense.presentation

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
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExpenseControllerTest : IntegrationTestSupport() {
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
                jdbc.update("DELETE FROM expense_share_amount WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM expense_item_member WHERE expense_id IN (SELECT id FROM expense WHERE trip_id = ?)", id)
                jdbc.update("DELETE FROM expense_item WHERE expense_id IN (SELECT id FROM expense WHERE trip_id = ?)", id)
                jdbc.update("DELETE FROM expense WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM settlement WHERE trip_id = ?", id)
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

        context("POST /api/v1/trips/{tripId}/expenses") {
            test("전체 1/N 지출을 등록하면 201, 지출 상세 Location과 금액을 소수점 이하 2자리 문자열로 담은 지출 상세를 받는다") {
                // given
                val trip = createTrip()
                val requestBody =
                    """
                    {
                      "name": "식사",
                      "splitType": "EQUAL",
                      "currency": "KRW",
                      "paidDate": "2026-11-05",
                      "paymentMethod": "CARD",
                      "payerTripMemberId": "${trip.adminId}",
                      "originalAmount": "12000.00",
                      "exchangeRate": "1.00",
                      "exchangeRateSource": "MANUAL",
                      "tripMemberIds": ["${trip.guestId}", "${trip.adminId}"]
                    }
                    """.trimIndent()

                // when
                val response = post(trip.tripId, requestBody, accessToken(trip.adminMemberId), UUID.randomUUID().toString())

                // then
                val expenseId = findExpenseIds(trip.tripId).single()
                assertSoftly {
                    response.statusCode() shouldBe 201
                    response.headers().firstValue("Location").orElse(null) shouldBe "/api/v1/trips/${trip.tripId}/expenses/$expenseId"
                    body(response) shouldBe
                        mapOf(
                            "data" to
                                mapOf(
                                    "expenseId" to expenseId.toString(),
                                    "name" to "식사",
                                    "paidDate" to "2026-11-05",
                                    "currency" to "KRW",
                                    "amount" to "12000.00",
                                    "payerTripMemberId" to trip.adminId.toString(),
                                    "settlementId" to null,
                                    "isEditLocked" to false,
                                    "tripId" to trip.tripId.toString(),
                                    "splitType" to "EQUAL",
                                    "createdByTripMemberId" to trip.adminId.toString(),
                                    "paymentMethod" to "CARD",
                                    "originalAmount" to "12000.00",
                                    "discountAmount" to "0.00",
                                    "exchangeRate" to "1.00",
                                    "exchangeRateSource" to "MANUAL",
                                    "exchangeRateProvider" to null,
                                    "exchangeRateBaseDate" to null,
                                    "amountKrw" to "12000.00",
                                    "items" to emptyList<Any>(),
                                    "shares" to
                                        listOf(
                                            mapOf(
                                                "tripMemberId" to trip.adminId.toString(),
                                                "amount" to "6000.00",
                                                "amountKrw" to "6000.00",
                                            ),
                                            mapOf(
                                                "tripMemberId" to trip.guestId.toString(),
                                                "amount" to "6000.00",
                                                "amountKrw" to "6000.00",
                                            ),
                                        ),
                                ),
                        )
                }
            }

            test("품목별 USD 지출을 등록하면 splitType BY_ITEM과 품목, 센트 단위 부담액과 원화 부담액을 받는다") {
                // given
                val trip = createTrip()
                val requestBody =
                    """
                    {
                      "name": "Dinner",
                      "splitType": "BY_ITEM",
                      "currency": "USD",
                      "paidDate": "2026-11-06",
                      "paymentMethod": "TRAVEL_CARD",
                      "payerTripMemberId": "${trip.adminId}",
                      "originalAmount": "12.34",
                      "discountAmount": "0.34",
                      "exchangeRate": "1380.55",
                      "exchangeRateSource": "AUTO",
                      "items": [
                        { "name": "피자", "amount": "10.00", "discountAmount": "1.00", "tripMemberIds": ["${trip.guestId}", "${trip.adminId}"] },
                        { "amount": "2.34", "discountAmount": "0.00", "tripMemberIds": ["${trip.adminId}"] }
                      ]
                    }
                    """.trimIndent()

                // when
                val response = post(trip.tripId, requestBody, accessToken(trip.adminMemberId), UUID.randomUUID().toString())

                // then
                // 최종 결제액 11.00달러를 13.68 : 9.00 비율로 나눠 6.63달러와 4.37달러, 원화 환산액 15186원을 9153원과 6033원으로 나눕니다.
                val data = body(response)["data"] as Map<*, *>
                val itemIds = findItemIds(findExpenseIds(trip.tripId).single())
                assertSoftly {
                    response.statusCode() shouldBe 201
                    data["splitType"] shouldBe "BY_ITEM"
                    data["currency"] shouldBe "USD"
                    data["paymentMethod"] shouldBe "TRAVEL_CARD"
                    data["exchangeRateSource"] shouldBe "AUTO"
                    data["exchangeRateProvider"] shouldBe null
                    data["originalAmount"] shouldBe "12.34"
                    data["discountAmount"] shouldBe "0.34"
                    data["amount"] shouldBe "11.00"
                    data["exchangeRate"] shouldBe "1380.55"
                    data["amountKrw"] shouldBe "15186.00"
                    data["items"] shouldBe
                        listOf(
                            mapOf(
                                "expenseItemId" to itemIds[0].toString(),
                                "name" to "피자",
                                "amount" to "10.00",
                                "discountAmount" to "1.00",
                                "sortOrder" to 0,
                                "tripMemberIds" to listOf(trip.adminId.toString(), trip.guestId.toString()),
                            ),
                            mapOf(
                                "expenseItemId" to itemIds[1].toString(),
                                "name" to null,
                                "amount" to "2.34",
                                "discountAmount" to "0.00",
                                "sortOrder" to 1,
                                "tripMemberIds" to listOf(trip.adminId.toString()),
                            ),
                        )
                    data["shares"] shouldBe
                        listOf(
                            mapOf("tripMemberId" to trip.adminId.toString(), "amount" to "6.63", "amountKrw" to "9153.00"),
                            mapOf("tripMemberId" to trip.guestId.toString(), "amount" to "4.37", "amountKrw" to "6033.00"),
                        )
                }
            }

            test("Access Token이 없으면 401 COMMON_UNAUTHORIZED를 반환하고 지출을 저장하지 않는다") {
                // given
                val trip = createTrip()

                // when
                val response = post(trip.tripId, validRequestBody(trip), token = null, UUID.randomUUID().toString())

                // then
                assertSoftly {
                    response.statusCode() shouldBe 401
                    body(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
                    findExpenseIds(trip.tripId) shouldBe emptyList()
                }
            }

            test("Idempotency-Key가 없거나 UUID 형식이 아니면 400 COMMON_INVALID_REQUEST를 반환하고 지출을 저장하지 않는다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                val idempotencyKeys =
                    mapOf(
                        "헤더 없음" to null,
                        "UUID가 아닌 문자열" to "expense-create-key",
                        "자릿수가 모자란 UUID" to "1-1-1-1-1",
                        "하이픈이 없는 UUID" to "3f1c2b8e6a4d4e2f9b7a1c5d8e0f2a6b",
                    )

                // when
                val responses = idempotencyKeys.mapValues { post(trip.tripId, validRequestBody(trip), accessToken, it.value) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe idempotencyKeys.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe idempotencyKeys.mapValues { "COMMON_INVALID_REQUEST" }
                    findExpenseIds(trip.tripId) shouldBe emptyList()
                }
            }

            test("형식, 통화 최소 단위나 분배 방식 조건을 벗어난 요청은 400 COMMON_INVALID_REQUEST를 반환하고 지출을 저장하지 않는다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                val valid = validRequest(trip)
                val item = mapOf("amount" to "12000.00", "discountAmount" to "0.00", "tripMemberIds" to listOf(trip.adminId.toString()))
                val validByItem = valid - "tripMemberIds" + mapOf("splitType" to "BY_ITEM", "items" to listOf(item))
                val requests =
                    mapOf(
                        "지출명 누락" to valid - "name",
                        "공백만 입력한 지출명" to valid + ("name" to "  "),
                        "51자 지출명" to valid + ("name" to "가".repeat(51)),
                        "DB 값인 분배 방식" to valid + ("splitType" to "ITEM"),
                        "허용하지 않는 통화" to valid + ("currency" to "VND"),
                        "날짜 형식이 아닌 결제 날짜" to valid + ("paidDate" to "2026/11/05"),
                        "허용하지 않는 결제 수단" to valid + ("paymentMethod" to "POINT"),
                        "숫자가 아닌 결제자 ID" to valid + ("payerTripMemberId" to "participant-a"),
                        "소수점이 없는 금액" to valid + ("originalAmount" to "12000"),
                        "소수점 이하 1자리 금액" to valid + ("originalAmount" to "12000.0"),
                        "쉼표가 있는 금액" to valid + ("originalAmount" to "12,000.00"),
                        "소수가 있는 KRW 금액" to valid + ("originalAmount" to "12000.50"),
                        "할인액 null" to valid + ("discountAmount" to null),
                        "1이 아닌 KRW 환율" to valid + ("exchangeRate" to "1.50"),
                        "소수점 이하 3자리 환율" to valid + ("exchangeRate" to "1.000"),
                        "허용하지 않는 환율 결정 방식" to valid + ("exchangeRateSource" to "BANK"),
                        "부담자 목록 null" to valid + ("tripMemberIds" to null),
                        "부담자 목록 안의 null" to valid + ("tripMemberIds" to listOf(trip.adminId.toString(), null)),
                        "숫자가 아닌 부담자 ID" to valid + ("tripMemberIds" to listOf("participant-a")),
                        "전체 1/N인데 부담자 목록 누락" to valid - "tripMemberIds",
                        "전체 1/N인데 품목 있음" to valid + ("items" to listOf(item)),
                        "품목별인데 품목 누락" to validByItem - "items",
                        "품목별인데 부담자 목록 있음" to validByItem + ("tripMemberIds" to listOf(trip.adminId.toString())),
                        "품목 금액 형식 위반" to validByItem + ("items" to listOf(item + ("amount" to "12000"))),
                        "품목 할인액 누락" to validByItem + ("items" to listOf(item - "discountAmount")),
                        "빈 품목명" to validByItem + ("items" to listOf(item + ("name" to ""))),
                        "명세에 없는 필드" to valid + ("memo" to "메모"),
                    )

                // when
                val responses =
                    requests.mapValues {
                        post(
                            trip.tripId,
                            jsonMapper.writeValueAsString(it.value),
                            accessToken,
                            UUID.randomUUID().toString(),
                        )
                    }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe requests.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe requests.mapValues { "COMMON_INVALID_REQUEST" }
                    findExpenseIds(trip.tripId) shouldBe emptyList()
                }
            }

            test("업무 규칙에 맞지 않는 요청은 지출 오류 코드와 안내 문구를 반환한다") {
                // given
                val trip = createTrip()
                val unconfirmedTrip = createTrip(status = "UNCONFIRMED")
                val valid = validRequest(trip)
                val requests =
                    mapOf(
                        "EXPENSE_INVALID_SHARES" to (trip to valid + ("payerTripMemberId" to trip.guestId.toString())),
                        "EXPENSE_AMOUNT_MISMATCH" to
                            (
                                trip to
                                    valid - "tripMemberIds" +
                                    mapOf(
                                        "splitType" to "BY_ITEM",
                                        "items" to
                                            listOf(
                                                mapOf(
                                                    "amount" to "1.00",
                                                    "discountAmount" to "0.00",
                                                    "tripMemberIds" to listOf(trip.adminId.toString()),
                                                ),
                                            ),
                                    )
                            ),
                        "EXPENSE_TRIP_NOT_CONFIRMED" to (unconfirmedTrip to validRequest(unconfirmedTrip)),
                    )

                // when
                val responses =
                    requests.mapValues { (_, request) ->
                        val (targetTrip, requestBody) = request
                        post(
                            targetTrip.tripId,
                            jsonMapper.writeValueAsString(requestBody),
                            accessToken(targetTrip.adminMemberId),
                            UUID.randomUUID().toString(),
                        )
                    }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe
                        mapOf("EXPENSE_INVALID_SHARES" to 400, "EXPENSE_AMOUNT_MISMATCH" to 400, "EXPENSE_TRIP_NOT_CONFIRMED" to 409)
                    responses.mapValues { body(it.value) } shouldBe
                        mapOf(
                            "EXPENSE_INVALID_SHARES" to errorBody("EXPENSE_INVALID_SHARES", "결제자나 부담자로 지정할 수 없는 참여자가 있습니다."),
                            "EXPENSE_AMOUNT_MISMATCH" to errorBody("EXPENSE_AMOUNT_MISMATCH", "품목 금액의 합계가 결제 총액과 다릅니다."),
                            "EXPENSE_TRIP_NOT_CONFIRMED" to errorBody("EXPENSE_TRIP_NOT_CONFIRMED", "여행을 확정한 뒤에 지출을 등록할 수 있습니다."),
                        )
                    findExpenseIds(trip.tripId) shouldBe emptyList()
                    findExpenseIds(unconfirmedTrip.tripId) shouldBe emptyList()
                }
            }

            test("다른 회원이 이미 쓴 Idempotency-Key로 요청하면 409 EXPENSE_IDEMPOTENCY_KEY_REUSED를 반환하고 기존 지출 정보를 주지 않는다") {
                // given
                val trip = createTrip()
                val idempotencyKey = UUID.randomUUID().toString()
                post(trip.tripId, validRequestBody(trip), accessToken(trip.adminMemberId), idempotencyKey)
                val otherMemberId = createMember()
                addTripMember(trip.tripId, otherMemberId, "지훈", "MEMBER", "MEMBER")

                // when
                val response = post(trip.tripId, validRequestBody(trip), accessToken(otherMemberId), idempotencyKey)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    body(response) shouldBe errorBody("EXPENSE_IDEMPOTENCY_KEY_REUSED", "이미 사용된 지출 등록 요청입니다. 다시 시도해주세요.")
                    findExpenseIds(trip.tripId).size shouldBe 1
                }
            }
        }

        context("GET /api/v1/trips/{tripId}/expenses") {
            test("지출 목록을 결제 날짜의 역순으로 받고 금액은 소수점 이하 2자리 문자열, 페이지 정보는 최상위 page로 받는다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                post(
                    trip.tripId,
                    jsonMapper.writeValueAsString(validRequest(trip) + ("paidDate" to "2026-11-06")),
                    accessToken,
                    UUID.randomUUID().toString(),
                )
                val later = findExpenseIds(trip.tripId).max()

                // when
                val response = get("/api/v1/trips/${trip.tripId}/expenses?size=1", accessToken)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe
                        mapOf(
                            "data" to
                                listOf(
                                    mapOf(
                                        "expenseId" to later.toString(),
                                        "name" to "식사",
                                        "paidDate" to "2026-11-06",
                                        "currency" to "KRW",
                                        "amount" to "12000.00",
                                        "payerTripMemberId" to trip.adminId.toString(),
                                        "settlementId" to null,
                                        "isEditLocked" to false,
                                    ),
                                ),
                            "page" to mapOf("page" to 0, "size" to 1, "totalElements" to 2, "hasNext" to true),
                        )
                }
            }

            test("페이지 조건이나 날짜 형식이 올바르지 않거나 fromDate가 toDate보다 뒤면 400 COMMON_INVALID_REQUEST를 반환한다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                val queries =
                    mapOf(
                        "음수 페이지" to "page=-1",
                        "0인 크기" to "size=0",
                        "100을 넘는 크기" to "size=101",
                        "형식이 다른 날짜" to "fromDate=2026/11/05",
                        "없는 날짜" to "toDate=2026-02-30",
                        "뒤집힌 기간" to "fromDate=2026-11-06&toDate=2026-11-05",
                    )

                // when
                val responses = queries.mapValues { get("/api/v1/trips/${trip.tripId}/expenses?${it.value}", accessToken) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe queries.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe queries.mapValues { "COMMON_INVALID_REQUEST" }
                }
            }
        }

        context("/api/v1/trips/{tripId}/expenses/{expenseId}/edit-lock") {
            test("잠금을 획득하면 201과 Location 없이 잠금 토큰과 초 단위 UTC 만료 시각을 받고, 지출 상세의 isEditLocked가 true가 된다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                val path = "/api/v1/trips/${trip.tripId}/expenses/${findExpenseIds(trip.tripId).single()}"

                // when
                val response = send("POST", "$path/edit-lock", accessToken)

                // then
                val data = body(response)["data"] as Map<*, *>
                assertSoftly {
                    response.statusCode() shouldBe 201
                    response.headers().firstValue("Location").isPresent shouldBe false
                    data.keys shouldBe setOf("lockToken", "expiresAt")
                    UUID.fromString(data["lockToken"] as String).toString() shouldBe data["lockToken"]
                    (data["expiresAt"] as String).matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")) shouldBe true
                    (body(get(path, accessToken))["data"] as Map<*, *>)["isEditLocked"] shouldBe true
                }
            }

            test("유효한 잠금이 있는 지출의 잠금을 다시 획득하면 409 EXPENSE_EDIT_LOCKED와 안내 문구를 받는다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                val path = "/api/v1/trips/${trip.tripId}/expenses/${findExpenseIds(trip.tripId).single()}/edit-lock"
                send("POST", path, accessToken)

                // when
                val response = send("POST", path, accessToken)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    body(response) shouldBe errorBody("EXPENSE_EDIT_LOCKED", "다른 참여자가 수정 중인 지출입니다.")
                }
            }

            test("X-Lock-Token으로 연장하면 200과 2분 늘어난 만료 시각, 해제하면 본문 없는 204를 받고 같은 해제를 다시 보내도 204를 받는다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                val path = "/api/v1/trips/${trip.tripId}/expenses/${findExpenseIds(trip.tripId).single()}/edit-lock"
                val lock = body(send("POST", path, accessToken))["data"] as Map<*, *>
                val lockToken = lock["lockToken"] as String

                // when
                val extended = send("PATCH", path, accessToken, lockToken)
                val released = send("DELETE", path, accessToken, lockToken)
                val releasedAgain = send("DELETE", path, accessToken, lockToken)
                val extendedAfterRelease = send("PATCH", path, accessToken, lockToken)

                // then
                assertSoftly {
                    extended.statusCode() shouldBe 200
                    body(extended)["data"] shouldBe
                        mapOf("expiresAt" to Instant.parse(lock["expiresAt"] as String).plus(Duration.ofMinutes(2)).toString())
                    released.statusCode() shouldBe 204
                    released.body() shouldBe ""
                    releasedAgain.statusCode() shouldBe 204
                    extendedAfterRelease.statusCode() shouldBe 409
                    body(extendedAfterRelease)["code"] shouldBe "EXPENSE_EDIT_LOCK_INVALID"
                }
            }

            test("X-Lock-Token이 없거나 UUID 형식이 아니면 400 COMMON_INVALID_REQUEST를 받는다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                val path = "/api/v1/trips/${trip.tripId}/expenses/${findExpenseIds(trip.tripId).single()}/edit-lock"
                val requests =
                    mapOf(
                        "연장, 헤더 없음" to Pair("PATCH", null),
                        "연장, 형식 오류" to Pair("PATCH", "lock-token"),
                        "해제, 헤더 없음" to Pair("DELETE", null),
                        "해제, 하이픈 없는 UUID" to Pair("DELETE", "5f0c7a2e9b1d4c3e8a6f2d4b6c8e0a1f"),
                    )

                // when
                val responses = requests.mapValues { send(it.value.first, path, accessToken, it.value.second) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe requests.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe requests.mapValues { "COMMON_INVALID_REQUEST" }
                }
            }
        }

        context("GET /api/v1/trips/{tripId}/expenses/{expenseId}") {
            test("등록 응답의 Location으로 조회하면 등록 응답과 같은 지출 상세를 받는다") {
                // given
                val trip = createTrip()
                val created = post(trip.tripId, validRequestBody(trip), accessToken(trip.adminMemberId), UUID.randomUUID().toString())
                val otherMemberId = createMember()
                addTripMember(trip.tripId, otherMemberId, "지훈", "MEMBER", "MEMBER")

                // when
                val response = get(created.headers().firstValue("Location").get(), accessToken(otherMemberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe body(created)
                }
            }

            test("숫자가 아닌 지출 ID는 400, 다른 여행의 지출은 404 COMMON_NOT_FOUND를 반환한다") {
                // given
                val trip = createTrip()
                val otherTrip = createTrip()
                post(otherTrip.tripId, validRequestBody(otherTrip), accessToken(otherTrip.adminMemberId), UUID.randomUUID().toString())
                val otherTripExpenseId = findExpenseIds(otherTrip.tripId).single()
                val accessToken = accessToken(trip.adminMemberId)

                // when
                val invalid = get("/api/v1/trips/${trip.tripId}/expenses/expense-a", accessToken)
                val notFound = get("/api/v1/trips/${trip.tripId}/expenses/$otherTripExpenseId", accessToken)

                // then
                assertSoftly {
                    invalid.statusCode() shouldBe 400
                    body(invalid)["code"] shouldBe "COMMON_INVALID_REQUEST"
                    notFound.statusCode() shouldBe 404
                    body(notFound) shouldBe errorBody("COMMON_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.")
                }
            }
        }

        context("PUT /api/v1/trips/{tripId}/expenses/{expenseId}") {
            test("잠금 토큰으로 품목별 USD 지출로 수정하면 200과 다시 계산한 지출 상세를 받고, 잠금이 풀려 상세 조회도 같은 값을 반환한다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                val expenseId = findExpenseIds(trip.tripId).single()
                val path = "/api/v1/trips/${trip.tripId}/expenses/$expenseId"
                val lockToken = (body(send("POST", "$path/edit-lock", accessToken))["data"] as Map<*, *>)["lockToken"] as String
                val requestBody =
                    """
                    {
                      "name": "Dinner",
                      "splitType": "BY_ITEM",
                      "currency": "USD",
                      "paidDate": "2026-11-06",
                      "paymentMethod": "TRAVEL_CARD",
                      "payerTripMemberId": "${trip.adminId}",
                      "originalAmount": "12.34",
                      "discountAmount": "0.34",
                      "exchangeRate": "1380.55",
                      "exchangeRateSource": "AUTO",
                      "items": [
                        { "name": "피자", "amount": "10.00", "discountAmount": "1.00", "tripMemberIds": ["${trip.guestId}", "${trip.adminId}"] },
                        { "amount": "2.34", "discountAmount": "0.00", "tripMemberIds": ["${trip.adminId}"] }
                      ]
                    }
                    """.trimIndent()

                // when
                val response = put(path, requestBody, accessToken, lockToken)

                // then
                // 최종 결제액 11.00달러를 13.68 : 9.00 비율로 나눠 6.63달러와 4.37달러, 원화 환산액 15186원을 9153원과 6033원으로 나눕니다.
                val itemIds = findItemIds(expenseId)
                assertSoftly {
                    response.statusCode() shouldBe 200
                    response.headers().firstValue("Location").isPresent shouldBe false
                    body(response)["data"] shouldBe
                        mapOf(
                            "expenseId" to expenseId.toString(),
                            "name" to "Dinner",
                            "paidDate" to "2026-11-06",
                            "currency" to "USD",
                            "amount" to "11.00",
                            "payerTripMemberId" to trip.adminId.toString(),
                            "settlementId" to null,
                            "isEditLocked" to false,
                            "tripId" to trip.tripId.toString(),
                            "splitType" to "BY_ITEM",
                            "createdByTripMemberId" to trip.adminId.toString(),
                            "paymentMethod" to "TRAVEL_CARD",
                            "originalAmount" to "12.34",
                            "discountAmount" to "0.34",
                            "exchangeRate" to "1380.55",
                            "exchangeRateSource" to "AUTO",
                            "exchangeRateProvider" to null,
                            "exchangeRateBaseDate" to null,
                            "amountKrw" to "15186.00",
                            "items" to
                                listOf(
                                    mapOf(
                                        "expenseItemId" to itemIds[0].toString(),
                                        "name" to "피자",
                                        "amount" to "10.00",
                                        "discountAmount" to "1.00",
                                        "sortOrder" to 0,
                                        "tripMemberIds" to listOf(trip.adminId.toString(), trip.guestId.toString()),
                                    ),
                                    mapOf(
                                        "expenseItemId" to itemIds[1].toString(),
                                        "name" to null,
                                        "amount" to "2.34",
                                        "discountAmount" to "0.00",
                                        "sortOrder" to 1,
                                        "tripMemberIds" to listOf(trip.adminId.toString()),
                                    ),
                                ),
                            "shares" to
                                listOf(
                                    mapOf("tripMemberId" to trip.adminId.toString(), "amount" to "6.63", "amountKrw" to "9153.00"),
                                    mapOf("tripMemberId" to trip.guestId.toString(), "amount" to "4.37", "amountKrw" to "6033.00"),
                                ),
                        )
                    body(get(path, accessToken)) shouldBe body(response)
                }
            }

            test("수정에 성공한 뒤 같은 X-Lock-Token으로 다시 보내거나 잠금 없이 수정하면 409 EXPENSE_EDIT_LOCK_INVALID와 안내 문구를 받는다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                val path = "/api/v1/trips/${trip.tripId}/expenses/${findExpenseIds(trip.tripId).single()}"
                val requestBody = jsonMapper.writeValueAsString(validRequest(trip) + ("name" to "저녁 식사"))

                // when
                val withoutLock = put(path, requestBody, accessToken, UUID.randomUUID().toString())
                val lockToken = (body(send("POST", "$path/edit-lock", accessToken))["data"] as Map<*, *>)["lockToken"] as String
                val updated = put(path, requestBody, accessToken, lockToken)
                val resent = put(path, requestBody, accessToken, lockToken)

                // then
                val lockInvalid = errorBody("EXPENSE_EDIT_LOCK_INVALID", "수정 가능 시간이 지났거나 다른 화면에서 수정을 시작했습니다. 다시 수정해주세요.")
                assertSoftly {
                    withoutLock.statusCode() shouldBe 409
                    body(withoutLock) shouldBe lockInvalid
                    updated.statusCode() shouldBe 200
                    resent.statusCode() shouldBe 409
                    body(resent) shouldBe lockInvalid
                    body(get(path, accessToken)) shouldBe body(updated)
                }
            }

            test("정산이 점유한 지출을 수정하면 409 EXPENSE_SETTLEMENT_LOCKED와 안내 문구를 받는다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                val expenseId = findExpenseIds(trip.tripId).single()
                val settlementId =
                    jdbc.queryForObject(
                        "INSERT INTO settlement (trip_id, settlement_method, status, created_at, updated_at) " +
                            "VALUES (?, 'SINGLE_CURRENCY', 'DRAFT', now(), now()) RETURNING id",
                        Long::class.java,
                        trip.tripId,
                    )
                jdbc.update("UPDATE expense SET settlement_id = ? WHERE id = ?", settlementId, expenseId)

                // when
                val response =
                    put(
                        "/api/v1/trips/${trip.tripId}/expenses/$expenseId",
                        validRequestBody(trip),
                        accessToken,
                        UUID.randomUUID().toString(),
                    )

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    body(response) shouldBe errorBody("EXPENSE_SETTLEMENT_LOCKED", "정산 중이거나 정산한 지출은 수정할 수 없습니다.")
                }
            }

            test("X-Lock-Token이 없거나 UUID 형식이 아니면 400, Access Token이 없으면 401을 반환하고 지출을 바꾸지 않는다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                val path = "/api/v1/trips/${trip.tripId}/expenses/${findExpenseIds(trip.tripId).single()}"
                val lockToken = (body(send("POST", "$path/edit-lock", accessToken))["data"] as Map<*, *>)["lockToken"] as String
                val before = body(get(path, accessToken))
                val requestBody = jsonMapper.writeValueAsString(validRequest(trip) + ("name" to "저녁 식사"))

                // when
                val withoutLockToken = put(path, requestBody, accessToken, lockToken = null)
                val malformedLockToken = put(path, requestBody, accessToken, lockToken.replace("-", ""))
                val withoutAccessToken = put(path, requestBody, token = null, lockToken)

                // then
                assertSoftly {
                    withoutLockToken.statusCode() shouldBe 400
                    body(withoutLockToken)["code"] shouldBe "COMMON_INVALID_REQUEST"
                    malformedLockToken.statusCode() shouldBe 400
                    body(malformedLockToken)["code"] shouldBe "COMMON_INVALID_REQUEST"
                    withoutAccessToken.statusCode() shouldBe 401
                    body(withoutAccessToken)["code"] shouldBe "COMMON_UNAUTHORIZED"
                    body(get(path, accessToken)) shouldBe before
                }
            }

            test("형식이나 업무 규칙에 맞지 않는 본문은 400과 오류 코드를 반환하고, 지출과 수정 잠금을 그대로 두어 같은 토큰으로 연장할 수 있다") {
                // given
                val trip = createTrip()
                val accessToken = accessToken(trip.adminMemberId)
                post(trip.tripId, validRequestBody(trip), accessToken, UUID.randomUUID().toString())
                val path = "/api/v1/trips/${trip.tripId}/expenses/${findExpenseIds(trip.tripId).single()}"
                val lockToken = (body(send("POST", "$path/edit-lock", accessToken))["data"] as Map<*, *>)["lockToken"] as String
                val before = body(get(path, accessToken))
                val valid = validRequest(trip)
                val requests =
                    mapOf(
                        "정의하지 않은 필드" to valid + ("shares" to emptyList<String>()),
                        "필수 필드 생략" to valid - "name",
                        "KRW의 소수 금액" to valid + ("originalAmount" to "12000.50"),
                        "비로그인 참여자를 결제자로" to valid + ("payerTripMemberId" to trip.guestId.toString()),
                        "품목 합계가 총액과 다름" to
                            valid - "tripMemberIds" +
                            mapOf(
                                "splitType" to "BY_ITEM",
                                "items" to
                                    listOf(
                                        mapOf(
                                            "amount" to "1.00",
                                            "discountAmount" to "0.00",
                                            "tripMemberIds" to listOf(trip.adminId.toString()),
                                        ),
                                    ),
                            ),
                    )

                // when
                val responses = requests.mapValues { put(path, jsonMapper.writeValueAsString(it.value), accessToken, lockToken) }
                val extended = send("PATCH", "$path/edit-lock", accessToken, lockToken)

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe requests.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe
                        mapOf(
                            "정의하지 않은 필드" to "COMMON_INVALID_REQUEST",
                            "필수 필드 생략" to "COMMON_INVALID_REQUEST",
                            "KRW의 소수 금액" to "COMMON_INVALID_REQUEST",
                            "비로그인 참여자를 결제자로" to "EXPENSE_INVALID_SHARES",
                            "품목 합계가 총액과 다름" to "EXPENSE_AMOUNT_MISMATCH",
                        )
                    body(responses.getValue("비로그인 참여자를 결제자로")) shouldBe
                        errorBody("EXPENSE_INVALID_SHARES", "결제자나 부담자로 지정할 수 없는 참여자가 있습니다.")
                    body(responses.getValue("품목 합계가 총액과 다름")) shouldBe
                        errorBody("EXPENSE_AMOUNT_MISMATCH", "품목 금액의 합계가 결제 총액과 다릅니다.")
                    extended.statusCode() shouldBe 200
                    body(get(path, accessToken)) shouldBe before
                }
            }
        }
    }

    /** 테스트가 끝나면 지웁니다. 확정 상태를 직접 정하려고 여행 생성 API 대신 DB에 바로 넣습니다. */
    private fun createTrip(status: String = "CONFIRMED"): TripFixture {
        val key = UUID.randomUUID()
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, invite_code, " +
                        "idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', '2026-11-05', '2026-11-07', 'JP', 'JPY', 3, ?::trip_status, ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    status,
                    key.toString().replace("-", "").take(20),
                    key,
                ),
            )
        tripIds.add(tripId)
        val adminMemberId = createMember()
        return TripFixture(
            tripId = tripId,
            adminMemberId = adminMemberId,
            adminId = addTripMember(tripId, adminMemberId, "민지", "ADMIN", "MEMBER"),
            guestId = addTripMember(tripId, null, "서연", "MEMBER", "GUEST"),
        )
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
        memberId: Long?,
        tripNickname: String,
        role: String,
        status: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?::trip_member_role, ?::trip_member_status, now(), now()) RETURNING id",
                Long::class.java,
                tripId,
                memberId,
                tripNickname,
                role,
                status,
            ),
        )

    private fun validRequest(trip: TripFixture): Map<String, Any?> =
        mapOf(
            "name" to "식사",
            "splitType" to "EQUAL",
            "currency" to "KRW",
            "paidDate" to "2026-11-05",
            "paymentMethod" to "CARD",
            "payerTripMemberId" to trip.adminId.toString(),
            "originalAmount" to "12000.00",
            "discountAmount" to "0.00",
            "exchangeRate" to "1.00",
            "exchangeRateSource" to "MANUAL",
            "tripMemberIds" to listOf(trip.adminId.toString(), trip.guestId.toString()),
        )

    private fun validRequestBody(trip: TripFixture): String = jsonMapper.writeValueAsString(validRequest(trip))

    private fun accessToken(memberId: Long): String = authTokenCreator.createMemberTokens(memberId).accessToken

    private fun findExpenseIds(tripId: Long): List<Long> =
        jdbc.queryForList("SELECT id FROM expense WHERE trip_id = ?", Long::class.java, tripId).map { checkNotNull(it) }

    private fun findItemIds(expenseId: Long): List<Long> =
        jdbc.queryForList("SELECT id FROM expense_item WHERE expense_id = ? ORDER BY sort_order", Long::class.java, expenseId).map {
            checkNotNull(it)
        }

    private fun post(
        tripId: Long,
        requestBody: String,
        token: String?,
        idempotencyKey: String?,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(
                    URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}/api/v1/trips/$tripId/expenses"),
                ).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        if (token != null) {
            request.header("Authorization", "Bearer $token")
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey)
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun put(
        path: String,
        requestBody: String,
        token: String?,
        lockToken: String?,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}$path"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
        if (token != null) {
            request.header("Authorization", "Bearer $token")
        }
        if (lockToken != null) {
            request.header("X-Lock-Token", lockToken)
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(
        path: String,
        token: String,
    ): HttpResponse<String> =
        httpClient.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}$path"))
                .header("Authorization", "Bearer $token")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun send(
        method: String,
        path: String,
        token: String,
        lockToken: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}$path"))
                .header("Authorization", "Bearer $token")
                .method(method, HttpRequest.BodyPublishers.noBody())
        if (lockToken != null) {
            request.header("X-Lock-Token", lockToken)
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun body(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)

    private fun errorBody(
        code: String,
        message: String,
    ): Map<String, Any?> = mapOf("code" to code, "message" to message, "errors" to null)

    private data class TripFixture(
        val tripId: Long,
        val adminMemberId: Long,
        val adminId: Long,
        val guestId: Long,
    )
}
