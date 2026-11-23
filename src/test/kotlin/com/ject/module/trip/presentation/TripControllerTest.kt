package com.ject.module.trip.presentation

import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
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
class TripControllerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    private val httpClient = HttpClient.newHttpClient()
    private val memberIds = mutableListOf<Long>()
    private val idempotencyKeys = mutableListOf<String>()
    private val tripIds = mutableListOf<Long>()

    init {
        afterEach {
            tripIds.forEach { id ->
                jdbc.update("DELETE FROM trip_member WHERE trip_id = ?", id)
                jdbc.update("DELETE FROM trip WHERE id = ?", id)
            }
            tripIds.clear()
            idempotencyKeys.forEach { key ->
                jdbc.update("DELETE FROM trip_member WHERE trip_id IN (SELECT id FROM trip WHERE idempotency_key = ?::uuid)", key)
                jdbc.update("DELETE FROM trip WHERE idempotency_key = ?::uuid", key)
            }
            idempotencyKeys.clear()
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        context("POST /api/v1/trips") {
            test("가입을 마친 회원이 여행을 만들면 201과 Location, 확정 전 여행 정보를 받고 생성자가 관리자이자 첫 참여자로 등록된다") {
                // given
                val memberId = createMember("ACTIVE")
                val idempotencyKey = newIdempotencyKey()
                val requestBody =
                    """
                    {
                      "name": "도쿄 여행",
                      "countryCode": "JP",
                      "baseCurrency": "JPY",
                      "startDate": "2026-11-05",
                      "endDate": "2026-11-07",
                      "memberCapacity": 3,
                      "tripNickname": "민지"
                    }
                    """.trimIndent()

                // when
                val response = post(requestBody, accessToken(memberId), idempotencyKey)

                // then
                val trip = findTrip(idempotencyKey)
                val tripMembers = findTripMembers(trip["id"] as Long)
                assertSoftly {
                    response.statusCode() shouldBe 201
                    response.headers().firstValue("Location").orElse(null) shouldBe "/api/v1/trips/${trip["id"]}"
                    body(response) shouldBe
                        mapOf(
                            "data" to
                                mapOf(
                                    "tripId" to trip["id"].toString(),
                                    "name" to "도쿄 여행",
                                    "countryCode" to "JP",
                                    "customCountryName" to null,
                                    "startDate" to "2026-11-05",
                                    "endDate" to "2026-11-07",
                                    "baseCurrency" to "JPY",
                                    "memberCapacity" to 3,
                                    "registeredMemberCount" to 1,
                                    "status" to "UNCONFIRMED",
                                    "myRole" to "ADMIN",
                                ),
                        )
                    trip["status"] shouldBe "UNCONFIRMED"
                    trip["confirmed_at"].shouldBeNull()
                    trip["deleted_at"].shouldBeNull()
                    (trip["invite_code"] as String) shouldMatch "[A-Z0-9]{6}"
                    tripMembers shouldBe
                        listOf(
                            mapOf(
                                "member_id" to memberId,
                                "trip_nickname" to "민지",
                                "role" to "ADMIN",
                                "joined_at" to null,
                                "status" to "MEMBER",
                                "left_at" to null,
                            ),
                        )
                }
            }

            test("기타 국가로 만들면 입력한 국가명을 그대로 저장하고 응답에 담는다") {
                // given
                val memberId = createMember("ACTIVE")
                val idempotencyKey = newIdempotencyKey()
                val requestBody =
                    """
                    {
                      "name": "하노이 여행",
                      "countryCode": "ETC",
                      "customCountryName": "베트남",
                      "baseCurrency": "USD",
                      "startDate": "2026-12-01",
                      "endDate": "2026-12-01",
                      "memberCapacity": 1,
                      "tripNickname": "Minji01"
                    }
                    """.trimIndent()

                // when
                val response = post(requestBody, accessToken(memberId), idempotencyKey)

                // then
                val data = body(response)["data"] as Map<*, *>
                val trip = findTrip(idempotencyKey)
                assertSoftly {
                    response.statusCode() shouldBe 201
                    data["countryCode"] shouldBe "ETC"
                    data["customCountryName"] shouldBe "베트남"
                    trip["country_code"] shouldBe "ETC"
                    trip["custom_country_name"] shouldBe "베트남"
                }
            }

            test("여행명과 기타 국가명의 앞뒤 공백을 지운 값으로 저장한다") {
                // given
                val memberId = createMember("ACTIVE")
                val idempotencyKey = newIdempotencyKey()
                val fifteenCharacterName = "가나다라마 바사아자차 ABC"
                val requestBody =
                    jsonMapper.writeValueAsString(
                        mapOf(
                            "name" to " \u3000\u001C$fifteenCharacterName\u001C\u3000 ",
                            "countryCode" to "ETC",
                            "customCountryName" to "\u3000\u001C Vietnam \u001C\u3000",
                            "baseCurrency" to "USD",
                            "startDate" to "2026-12-01",
                            "endDate" to "2026-12-04",
                            "memberCapacity" to 10,
                            "tripNickname" to "민지",
                        ),
                    )

                // when
                val response = post(requestBody, accessToken(memberId), idempotencyKey)

                // then
                val data = body(response)["data"] as Map<*, *>
                val trip = findTrip(idempotencyKey)
                assertSoftly {
                    response.statusCode() shouldBe 201
                    fifteenCharacterName.length shouldBe 15
                    data["name"] shouldBe fifteenCharacterName
                    data["customCountryName"] shouldBe "Vietnam"
                    trip["name"] shouldBe fifteenCharacterName
                    trip["custom_country_name"] shouldBe "Vietnam"
                }
            }

            test("Access Token이 없거나 가입 전용 토큰이면 401 COMMON_UNAUTHORIZED를 반환하고 여행을 만들지 않는다") {
                // given
                val pendingMemberId = createMember("PENDING")
                val idempotencyKeyWithoutToken = newIdempotencyKey()
                val idempotencyKeyWithRegistrationToken = newIdempotencyKey()

                // when
                val responses =
                    mapOf(
                        "Access Token 없음" to post(VALID_REQUEST_BODY, token = null, idempotencyKeyWithoutToken),
                        "가입 전용 토큰" to
                            post(
                                VALID_REQUEST_BODY,
                                authTokenCreator.createRegistrationToken(pendingMemberId),
                                idempotencyKeyWithRegistrationToken,
                            ),
                    )

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe responses.mapValues { 401 }
                    responses.mapValues { body(it.value)["code"] } shouldBe responses.mapValues { "COMMON_UNAUTHORIZED" }
                    countTrips(idempotencyKeyWithoutToken) shouldBe 0
                    countTrips(idempotencyKeyWithRegistrationToken) shouldBe 0
                }
            }

            test("Idempotency-Key가 없거나 UUID 형식이 아니면 400 COMMON_INVALID_REQUEST를 반환하고 여행을 만들지 않는다") {
                // given
                val memberId = createMember("ACTIVE")
                val accessToken = accessToken(memberId)
                val idempotencyKeys =
                    mapOf(
                        "헤더 없음" to null,
                        "UUID가 아닌 문자열" to "trip-create-key",
                        "자릿수가 모자란 UUID" to "1-1-1-1-1",
                        "하이픈이 없는 UUID" to "3f1c2b8e6a4d4e2f9b7a1c5d8e0f2a6b",
                        "한 글자 더 긴 UUID" to "3f1c2b8e-6a4d-4e2f-9b7a-1c5d8e0f2a6b0",
                    )

                // when
                val responses = idempotencyKeys.mapValues { post(VALID_REQUEST_BODY, accessToken, it.value) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe idempotencyKeys.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe idempotencyKeys.mapValues { "COMMON_INVALID_REQUEST" }
                    countTripMembers(memberId) shouldBe 0
                }
            }

            test("형식이나 범위가 틀린 여행 생성 요청은 400 COMMON_INVALID_REQUEST를 반환하고 여행을 만들지 않는다") {
                // given
                val memberId = createMember("ACTIVE")
                val accessToken = accessToken(memberId)
                val valid =
                    mapOf(
                        "name" to "도쿄 여행",
                        "countryCode" to "JP",
                        "baseCurrency" to "JPY",
                        "startDate" to "2026-11-05",
                        "endDate" to "2026-11-07",
                        "memberCapacity" to 3,
                        "tripNickname" to "민지",
                    )
                val validCustomCountry = valid + mapOf("countryCode" to "ETC", "customCountryName" to "베트남")
                val requests =
                    mapOf(
                        "여행명 누락" to valid - "name",
                        "여행명 null" to valid + ("name" to null),
                        "빈 여행명" to valid + ("name" to ""),
                        "공백만 입력한 여행명" to valid + ("name" to "   "),
                        "trim이 지우지 않는 제어 문자가 있는 여행명" to valid + ("name" to "\u0085도쿄 여행\u0085"),
                        "16자 여행명" to valid + ("name" to "가".repeat(16)),
                        "특수문자가 있는 여행명" to valid + ("name" to "도쿄 여행!"),
                        "한글 자모가 있는 여행명" to valid + ("name" to "ㅋㅋ 여행"),
                        "대표 국가 누락" to valid - "countryCode",
                        "허용하지 않는 대표 국가" to valid + ("countryCode" to "VN"),
                        "기타 국가인데 국가명 누락" to validCustomCountry - "customCountryName",
                        "기타 국가인데 국가명 null" to validCustomCountry + ("customCountryName" to null),
                        "기타 국가가 아닌데 국가명 입력" to valid + ("customCountryName" to "베트남"),
                        "기타 국가가 아닌데 국가명 null" to valid + ("customCountryName" to null),
                        "공백만 입력한 기타 국가명" to validCustomCountry + ("customCountryName" to "  "),
                        "trim이 지우지 않는 제어 문자가 있는 기타 국가명" to validCustomCountry + ("customCountryName" to "\u0085베트남\u0085"),
                        "11자 기타 국가명" to validCustomCountry + ("customCountryName" to "가".repeat(11)),
                        "숫자가 있는 기타 국가명" to validCustomCountry + ("customCountryName" to "베트남1"),
                        "가운데 공백이 있는 기타 국가명" to validCustomCountry + ("customCountryName" to "뉴 질랜드"),
                        "기본 통화 누락" to valid - "baseCurrency",
                        "허용하지 않는 기본 통화" to valid + ("baseCurrency" to "VND"),
                        "시작일 누락" to valid - "startDate",
                        "날짜 형식이 아닌 시작일" to valid + ("startDate" to "2026/11/05"),
                        "없는 날짜인 종료일" to valid + ("endDate" to "2026-11-31"),
                        "시작일보다 빠른 종료일" to valid + ("endDate" to "2026-11-04"),
                        "정원 누락" to valid - "memberCapacity",
                        "정원 null" to valid + ("memberCapacity" to null),
                        "정원 0명" to valid + ("memberCapacity" to 0),
                        "정원 11명" to valid + ("memberCapacity" to 11),
                        "숫자가 아닌 정원" to valid + ("memberCapacity" to "three"),
                        "닉네임 누락" to valid - "tripNickname",
                        "빈 닉네임" to valid + ("tripNickname" to ""),
                        "11자 닉네임" to valid + ("tripNickname" to "가".repeat(11)),
                        "앞뒤 공백이 있는 닉네임" to valid + ("tripNickname" to " 민지 "),
                        "특수문자가 있는 닉네임" to valid + ("tripNickname" to "민지!"),
                        "한글 자모가 있는 닉네임" to valid + ("tripNickname" to "ㅁㅈ"),
                        "명세에 없는 필드" to valid + ("memo" to "메모"),
                    )

                // when
                val responses =
                    requests.mapValues { post(jsonMapper.writeValueAsString(it.value), accessToken, newIdempotencyKey()) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe requests.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe requests.mapValues { "COMMON_INVALID_REQUEST" }
                    countTripMembers(memberId) shouldBe 0
                }
            }

            test("같은 회원이 같은 Idempotency-Key로 다시 요청하면 여행을 새로 만들지 않고 201과 Location, 처음 만든 여행의 현재 정보를 받는다") {
                // given
                val memberId = createMember("ACTIVE")
                val accessToken = accessToken(memberId)
                val idempotencyKey = newIdempotencyKey()
                val first = post(VALID_REQUEST_BODY, accessToken, idempotencyKey)
                val tripId = findTrip(idempotencyKey)["id"] as Long
                // 처음 응답 뒤에 비로그인 참여자가 한 명 등록된 상태를 만듭니다.
                jdbc.update(
                    "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, joined_at, status, created_at, updated_at) " +
                        "VALUES (?, NULL, '서연', 'MEMBER'::trip_member_role, NULL, 'GUEST'::trip_member_status, now(), now())",
                    tripId,
                )

                // when
                val second = post(VALID_REQUEST_BODY, accessToken, idempotencyKey)

                // then
                val firstData = body(first)["data"] as Map<*, *>
                assertSoftly {
                    second.statusCode() shouldBe 201
                    second.headers().firstValue("Location").orElse(null) shouldBe "/api/v1/trips/$tripId"
                    firstData["registeredMemberCount"] shouldBe 1
                    body(second) shouldBe mapOf("data" to firstData + mapOf("registeredMemberCount" to 2))
                    countTrips(idempotencyKey) shouldBe 1
                    countTripMembers(memberId) shouldBe 1
                }
            }

            test("같은 회원이 같은 Idempotency-Key로 본문을 바꿔 요청해도 처음 만든 여행을 받는다") {
                // given
                val memberId = createMember("ACTIVE")
                val accessToken = accessToken(memberId)
                val idempotencyKey = newIdempotencyKey()
                val first = post(VALID_REQUEST_BODY, accessToken, idempotencyKey)
                val changedRequestBody =
                    """
                    {
                      "name": "오사카 여행",
                      "countryCode": "ETC",
                      "customCountryName": "베트남",
                      "baseCurrency": "USD",
                      "startDate": "2027-01-01",
                      "endDate": "2027-01-03",
                      "memberCapacity": 5,
                      "tripNickname": "지훈"
                    }
                    """.trimIndent()

                // when
                val second = post(changedRequestBody, accessToken, idempotencyKey)

                // then
                assertSoftly {
                    first.statusCode() shouldBe 201
                    second.statusCode() shouldBe 201
                    second.headers().firstValue("Location") shouldBe first.headers().firstValue("Location")
                    body(second) shouldBe body(first)
                    findTrip(idempotencyKey)["name"] shouldBe "도쿄 여행"
                    countTrips(idempotencyKey) shouldBe 1
                    countTripMembers(memberId) shouldBe 1
                }
            }

            test("다른 회원이 이미 쓴 Idempotency-Key로 요청하면 409 TRIP_IDEMPOTENCY_KEY_REUSED를 반환하고 기존 여행 정보를 주지 않는다") {
                // given
                val creatorId = createMember("ACTIVE")
                val otherMemberId = createMember("ACTIVE")
                val idempotencyKey = newIdempotencyKey()
                post(VALID_REQUEST_BODY, accessToken(creatorId), idempotencyKey)

                // when
                val response = post(VALID_REQUEST_BODY, accessToken(otherMemberId), idempotencyKey)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    response.headers().firstValue("Location").isPresent shouldBe false
                    body(response) shouldBe
                        mapOf(
                            "code" to "TRIP_IDEMPOTENCY_KEY_REUSED",
                            "message" to "이미 사용된 여행 생성 요청입니다. 다시 시도해주세요.",
                            "errors" to null,
                        )
                    countTrips(idempotencyKey) shouldBe 1
                    countTripMembers(creatorId) shouldBe 1
                    countTripMembers(otherMemberId) shouldBe 0
                }
            }

            test("삭제된 여행의 Idempotency-Key로 다시 요청하면 409 TRIP_IDEMPOTENCY_KEY_REUSED를 반환한다") {
                // given
                val memberId = createMember("ACTIVE")
                val accessToken = accessToken(memberId)
                val idempotencyKey = newIdempotencyKey()
                post(VALID_REQUEST_BODY, accessToken, idempotencyKey)
                jdbc.update("UPDATE trip SET deleted_at = now() WHERE idempotency_key = ?::uuid", idempotencyKey)

                // when
                val response = post(VALID_REQUEST_BODY, accessToken, idempotencyKey)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    body(response)["code"] shouldBe "TRIP_IDEMPOTENCY_KEY_REUSED"
                    countTrips(idempotencyKey) shouldBe 1
                    countTripMembers(memberId) shouldBe 1
                }
            }
        }

        context("GET /api/v1/trips") {
            test("참여 중인 여행을 오늘 날짜로 계산한 일정 상태와 페이지 정보와 함께 반환한다") {
                // given
                val memberId = createMember("ACTIVE")
                val tripId = createTrip(status = "CONFIRMED", startDate = "2026-11-05", endDate = "2026-11-07", customCountryName = "베트남")
                addTripMember(tripId, memberId, "민지", role = "ADMIN", status = "MEMBER")
                addTripMember(tripId, null, "서연", role = "MEMBER", status = "GUEST")

                // when
                val response = get("/api/v1/trips?today=2026-11-07&filter=ONGOING&page=0&size=1", accessToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe
                        mapOf(
                            "data" to
                                listOf(
                                    mapOf(
                                        "tripId" to tripId.toString(),
                                        "name" to "하노이 여행",
                                        "countryCode" to "ETC",
                                        "customCountryName" to "베트남",
                                        "startDate" to "2026-11-05",
                                        "endDate" to "2026-11-07",
                                        "memberCapacity" to 3,
                                        "registeredMemberCount" to 2,
                                        "status" to "CONFIRMED",
                                        "scheduleStatus" to "ONGOING",
                                        "myRole" to "ADMIN",
                                        "myShareAmountKrw" to "0.00",
                                    ),
                                ),
                            "page" to mapOf("page" to 0, "size" to 1, "totalElements" to 1, "hasNext" to false),
                        )
                }
            }

            test("참여 중인 여행이 없으면 빈 목록과 기본 페이지 정보를 반환한다") {
                // given
                val memberId = createMember("ACTIVE")

                // when
                val response = get("/api/v1/trips?today=2026-11-07", accessToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe
                        mapOf(
                            "data" to emptyList<Any>(),
                            "page" to mapOf("page" to 0, "size" to 20, "totalElements" to 0, "hasNext" to false),
                        )
                }
            }

            test("today가 없거나 YYYY-MM-DD 날짜가 아니거나 페이지 조건과 필터가 범위를 벗어나면 400 COMMON_INVALID_REQUEST를 반환한다") {
                // given
                val accessToken = accessToken(createMember("ACTIVE"))
                val queries =
                    mapOf(
                        "today 누락" to "",
                        "빈 today" to "today=",
                        "자릿수가 모자란 날짜" to "today=2026-1-5",
                        "없는 날짜" to "today=2026-02-30",
                        "구분자 없는 날짜" to "today=20261105",
                        "시각이 붙은 날짜" to "today=2026-11-05T00:00:00Z",
                        "음수 page" to "today=2026-11-05&page=-1",
                        "숫자가 아닌 page" to "today=2026-11-05&page=first",
                        "size 0" to "today=2026-11-05&size=0",
                        "size 101" to "today=2026-11-05&size=101",
                        "허용하지 않는 filter" to "today=2026-11-05&filter=ALL",
                        "소문자 filter" to "today=2026-11-05&filter=ongoing",
                    )

                // when
                val responses = queries.mapValues { get("/api/v1/trips?${it.value}", accessToken) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe queries.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe queries.mapValues { "COMMON_INVALID_REQUEST" }
                    body(responses.getValue("today 누락"))["errors"] shouldBe listOf(mapOf("field" to "today", "reason" to "필수 값입니다."))
                }
            }

            test("Access Token이 없으면 401 COMMON_UNAUTHORIZED를 반환한다") {
                // when
                val response = get("/api/v1/trips?today=2026-11-05", token = null)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 401
                    body(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
                }
            }
        }

        context("GET /api/v1/trips/{tripId}") {
            test("참여 중인 회원은 여행 기본 정보와 참여자 목록을 받는다") {
                // given
                val adminId = createMember("ACTIVE")
                val memberId = createMember("ACTIVE")
                val tripId = createTrip(status = "UNCONFIRMED", startDate = "2026-11-05", endDate = "2026-11-07", customCountryName = null)
                val adminTripMemberId = addTripMember(tripId, adminId, "민지", role = "ADMIN", status = "MEMBER")
                val memberTripMemberId = addTripMember(tripId, memberId, "지훈", role = "MEMBER", status = "MEMBER")
                val guestTripMemberId = addTripMember(tripId, null, "서연", role = "MEMBER", status = "GUEST")

                // when
                val response = get("/api/v1/trips/$tripId", accessToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe
                        mapOf(
                            "data" to
                                mapOf(
                                    "tripId" to tripId.toString(),
                                    "name" to "하노이 여행",
                                    "countryCode" to "JP",
                                    "customCountryName" to null,
                                    "startDate" to "2026-11-05",
                                    "endDate" to "2026-11-07",
                                    "baseCurrency" to "JPY",
                                    "memberCapacity" to 3,
                                    "registeredMemberCount" to 3,
                                    "status" to "UNCONFIRMED",
                                    "myRole" to "MEMBER",
                                    "participants" to
                                        listOf(
                                            participant(adminTripMemberId, adminId, "민지", "ADMIN", "MEMBER"),
                                            participant(memberTripMemberId, memberId, "지훈", "MEMBER", "MEMBER"),
                                            participant(guestTripMemberId, null, "서연", "MEMBER", "GUEST"),
                                        ),
                                ),
                        )
                }
            }

            test("여행이 없으면 404, 참여하지 않은 회원이면 403, 여행 ID가 숫자가 아니면 400을 반환한다") {
                // given
                val adminId = createMember("ACTIVE")
                val outsiderId = createMember("ACTIVE")
                val tripId = createTrip(status = "UNCONFIRMED", startDate = "2026-11-05", endDate = "2026-11-07", customCountryName = null)
                addTripMember(tripId, adminId, "민지", role = "ADMIN", status = "MEMBER")
                val requests =
                    mapOf(
                        "없는 여행" to ("/api/v1/trips/${tripId + 1_000_000}" to accessToken(adminId)),
                        "참여하지 않은 회원" to ("/api/v1/trips/$tripId" to accessToken(outsiderId)),
                        "숫자가 아닌 여행 ID" to ("/api/v1/trips/trip-1" to accessToken(adminId)),
                    )

                // when
                val responses = requests.mapValues { get(it.value.first, it.value.second) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe
                        mapOf("없는 여행" to 404, "참여하지 않은 회원" to 403, "숫자가 아닌 여행 ID" to 400)
                    responses.mapValues { body(it.value)["code"] } shouldBe
                        mapOf("없는 여행" to "COMMON_NOT_FOUND", "참여하지 않은 회원" to "COMMON_FORBIDDEN", "숫자가 아닌 여행 ID" to "COMMON_INVALID_REQUEST")
                    body(responses.getValue("숫자가 아닌 여행 ID"))["errors"] shouldBe
                        listOf(mapOf("field" to "tripId", "reason" to "허용되지 않는 값입니다."))
                }
            }
        }
    }

    private fun createMember(status: String): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) " +
                        "VALUES ('GOOGLE', ?::member_status, now(), now()) RETURNING id",
                    Long::class.java,
                    status,
                ),
            )
        memberIds.add(id)
        return id
    }

    /** 테스트가 끝나면 지웁니다. 확정 상태와 날짜를 직접 정해야 해서 여행 생성 API 대신 DB에 바로 넣습니다. */
    private fun createTrip(
        status: String,
        startDate: String,
        endDate: String,
        customCountryName: String?,
    ): Long {
        val key = UUID.randomUUID()
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, custom_country_name, base_currency, member_capacity, " +
                        "status, invite_code, idempotency_key, created_at, updated_at) " +
                        "VALUES ('하노이 여행', ?::date, ?::date, ?, ?, 'JPY', 3, ?::trip_status, ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    startDate,
                    endDate,
                    if (customCountryName == null) "JP" else "ETC",
                    customCountryName,
                    status,
                    key.toString().replace("-", "").take(20),
                    key,
                ),
            )
        tripIds.add(id)
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

    private fun participant(
        tripMemberId: Long,
        memberId: Long?,
        displayName: String,
        role: String,
        participantStatus: String,
    ): Map<String, Any?> =
        mapOf(
            "tripMemberId" to tripMemberId.toString(),
            "memberId" to memberId?.toString(),
            "displayName" to displayName,
            "role" to role,
            "participantStatus" to participantStatus,
            "shareAmountKrw" to "0.00",
        )

    private fun accessToken(memberId: Long): String = authTokenCreator.createMemberTokens(memberId).accessToken

    /** 테스트가 끝나면 이 키로 만든 여행을 지웁니다. */
    private fun newIdempotencyKey(): String {
        val idempotencyKey = UUID.randomUUID().toString()
        idempotencyKeys.add(idempotencyKey)
        return idempotencyKey
    }

    private fun findTrip(idempotencyKey: String): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT id, name, country_code::text AS country_code, custom_country_name, status::text AS status, " +
                "confirmed_at, deleted_at, invite_code FROM trip WHERE idempotency_key = ?::uuid",
            idempotencyKey,
        )

    private fun findTripMembers(tripId: Long): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT member_id, trip_nickname, role::text AS role, joined_at, status::text AS status, left_at " +
                "FROM trip_member WHERE trip_id = ? ORDER BY id",
            tripId,
        )

    private fun countTrips(idempotencyKey: String): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM trip WHERE idempotency_key = ?::uuid", Int::class.java, idempotencyKey))

    private fun countTripMembers(memberId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM trip_member WHERE member_id = ?", Int::class.java, memberId))

    private fun post(
        requestBody: String,
        token: String?,
        idempotencyKey: String?,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}/api/v1/trips"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        if (token != null) {
            request.header("Authorization", "Bearer $token")
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey)
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun get(
        path: String,
        token: String?,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}$path"))
                .GET()
        if (token != null) {
            request.header("Authorization", "Bearer $token")
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun body(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)

    companion object {
        private val VALID_REQUEST_BODY =
            """
            {
              "name": "도쿄 여행",
              "countryCode": "JP",
              "baseCurrency": "JPY",
              "startDate": "2026-11-05",
              "endDate": "2026-11-07",
              "memberCapacity": 3,
              "tripNickname": "민지"
            }
            """.trimIndent()
    }
}
