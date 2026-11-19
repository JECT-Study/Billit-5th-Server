package com.ject.module.trip.presentation

import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripInvitationControllerTest : IntegrationTestSupport() {
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

        context("GET /api/v1/trips/{tripId}/invitation") {
            test("확정 전 여행의 관리자가 조회하면 200과 초대 코드, 초대 링크를 받는다") {
                // given
                val trip = createTrip(memberCapacity = 3)

                // when
                val response = get("/api/v1/trips/${trip.id}/invitation", accessToken(trip.adminMemberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe
                        mapOf(
                            "data" to
                                mapOf(
                                    "inviteCode" to trip.inviteCode,
                                    "inviteUrl" to "https://billit.test/invitations/${trip.inviteCode}",
                                ),
                        )
                }
            }

            test("관리자가 아닌 참여자나 참여하지 않은 회원이 조회하면 403 COMMON_FORBIDDEN을 받는다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val participantId = createMember("ACTIVE")
                addMember(trip.id, participantId, "지훈")
                val outsiderId = createMember("ACTIVE")

                // when
                val responses =
                    mapOf(
                        "일반 참여자" to get("/api/v1/trips/${trip.id}/invitation", accessToken(participantId)),
                        "참여하지 않은 회원" to get("/api/v1/trips/${trip.id}/invitation", accessToken(outsiderId)),
                    )

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe responses.mapValues { 403 }
                    responses.mapValues { body(it.value)["code"] } shouldBe responses.mapValues { "COMMON_FORBIDDEN" }
                }
            }

            test("없거나 삭제된 여행이면 404 COMMON_NOT_FOUND를, 확정된 여행이면 409 TRIP_ALREADY_CONFIRMED를 받는다") {
                // given
                val deletedTrip = createTrip(memberCapacity = 3, deleted = true)
                val confirmedTrip = createTrip(memberCapacity = 3, status = "CONFIRMED")
                val missingTripId = Long.MAX_VALUE

                // when
                val missingResponse = get("/api/v1/trips/$missingTripId/invitation", accessToken(deletedTrip.adminMemberId))
                val deletedResponse = get("/api/v1/trips/${deletedTrip.id}/invitation", accessToken(deletedTrip.adminMemberId))
                val confirmedResponse = get("/api/v1/trips/${confirmedTrip.id}/invitation", accessToken(confirmedTrip.adminMemberId))

                // then
                assertSoftly {
                    missingResponse.statusCode() shouldBe 404
                    body(missingResponse)["code"] shouldBe "COMMON_NOT_FOUND"
                    deletedResponse.statusCode() shouldBe 404
                    body(deletedResponse)["code"] shouldBe "COMMON_NOT_FOUND"
                    confirmedResponse.statusCode() shouldBe 409
                    body(confirmedResponse)["code"] shouldBe "TRIP_ALREADY_CONFIRMED"
                }
            }
        }

        context("GET /api/v1/trip-invitations") {
            test("초대 코드로 검색하면 여행 정보와 참여 여부를 받고 참여자는 늘지 않는다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val joinedMemberId = createMember("ACTIVE")
                addMember(trip.id, joinedMemberId, "지훈")
                val newMemberId = createMember("ACTIVE")

                // when
                val newMemberResponse = search(trip.inviteCode, accessToken(newMemberId))
                val joinedMemberResponse = search(trip.inviteCode, accessToken(joinedMemberId))

                // then
                assertSoftly {
                    newMemberResponse.statusCode() shouldBe 200
                    body(newMemberResponse) shouldBe
                        mapOf(
                            "data" to
                                mapOf(
                                    "tripId" to trip.id.toString(),
                                    "name" to "도쿄 여행",
                                    "startDate" to "2026-11-05",
                                    "endDate" to "2026-11-07",
                                    "registeredMemberCount" to 2,
                                    "memberCapacity" to 3,
                                    "status" to "UNCONFIRMED",
                                    "isAlreadyJoined" to false,
                                ),
                        )
                    (body(joinedMemberResponse)["data"] as Map<*, *>)["isAlreadyJoined"] shouldBe true
                    countTripMembers(trip.id) shouldBe 2
                }
            }

            test("앞뒤 공백과 소문자가 섞인 코드로도 같은 여행을 찾는다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val memberId = createMember("ACTIVE")

                // when
                val response = search("  ${trip.inviteCode.lowercase()} ", accessToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    (body(response)["data"] as Map<*, *>)["tripId"] shouldBe trip.id.toString()
                }
            }

            test("정원이 찼거나 확정된 여행도 검색 결과를 반환한다") {
                // given
                val fullTrip = createTrip(memberCapacity = 1)
                val confirmedTrip = createTrip(memberCapacity = 1, status = "CONFIRMED")
                val memberId = createMember("ACTIVE")

                // when
                val fullResponse = search(fullTrip.inviteCode, accessToken(memberId))
                val confirmedResponse = search(confirmedTrip.inviteCode, accessToken(memberId))

                // then
                val fullData = body(fullResponse)["data"] as Map<*, *>
                val confirmedData = body(confirmedResponse)["data"] as Map<*, *>
                assertSoftly {
                    fullResponse.statusCode() shouldBe 200
                    fullData["registeredMemberCount"] shouldBe 1
                    fullData["memberCapacity"] shouldBe 1
                    confirmedResponse.statusCode() shouldBe 200
                    confirmedData["status"] shouldBe "CONFIRMED"
                }
            }

            test("없는 코드, 형식이 틀린 코드, 삭제된 여행의 코드는 404 TRIP_INVITATION_NOT_FOUND를 받는다") {
                // given
                val deletedTrip = createTrip(memberCapacity = 3, deleted = true)
                val memberId = createMember("ACTIVE")
                val codes =
                    mapOf(
                        "없는 코드" to newInviteCode(),
                        "형식이 틀린 코드" to "AB-12",
                        "삭제된 여행의 코드" to deletedTrip.inviteCode,
                    )

                // when
                val responses = codes.mapValues { search(it.value, accessToken(memberId)) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe responses.mapValues { 404 }
                    responses.mapValues { body(it.value)["code"] } shouldBe responses.mapValues { "TRIP_INVITATION_NOT_FOUND" }
                }
            }

            test("code가 없거나 공백뿐이면 400 COMMON_INVALID_REQUEST를 받는다") {
                // given
                val memberId = createMember("ACTIVE")

                // when
                val responses =
                    mapOf(
                        "code 없음" to get("/api/v1/trip-invitations", accessToken(memberId)),
                        "공백뿐인 code" to search("   ", accessToken(memberId)),
                    )

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe responses.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe responses.mapValues { "COMMON_INVALID_REQUEST" }
                }
            }
        }

        context("POST /api/v1/trip-memberships") {
            test("확정 전이고 빈자리가 있는 여행에 참여하면 201과 참여 정보를 받고 입력한 닉네임의 일반 참여자로 등록된다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val memberId = createMember("ACTIVE")

                // when
                val response = join(trip.inviteCode, "지훈", accessToken(memberId))

                // then
                val joined = findTripMember(trip.id, memberId).shouldNotBeNull()
                assertSoftly {
                    response.statusCode() shouldBe 201
                    response
                        .headers()
                        .firstValue("Location")
                        .orElse(null)
                        .shouldBeNull()
                    body(response) shouldBe
                        mapOf("data" to mapOf("tripId" to trip.id.toString(), "tripMemberId" to joined["id"].toString()))
                    joined["trip_nickname"] shouldBe "지훈"
                    joined["role"] shouldBe "MEMBER"
                    joined["joined_at"].shouldNotBeNull()
                    countTripMembers(trip.id) shouldBe 2
                }
            }

            test("앞뒤 공백과 소문자가 섞인 초대 코드로도 같은 여행에 참여한다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val memberId = createMember("ACTIVE")
                val inputCode = "  ${trip.inviteCode.lowercase()} "

                // when
                val response = join(inputCode, "지훈", accessToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 201
                    findTripMember(trip.id, memberId).shouldNotBeNull()
                }
            }

            test("이미 참여 중인 회원이 다시 요청하면 확정이나 정원과 관계없이 새로 등록하지 않고 200과 기존 참여 정보를 받는다") {
                // given
                val trip = createTrip(memberCapacity = 2, status = "CONFIRMED")
                val memberId = createMember("ACTIVE")
                val tripMemberId = addMember(trip.id, memberId, "지훈")

                // when
                val response = join(trip.inviteCode, "다른닉네임", accessToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe
                        mapOf("data" to mapOf("tripId" to trip.id.toString(), "tripMemberId" to tripMemberId.toString()))
                    findTripMember(trip.id, memberId)?.get("trip_nickname") shouldBe "지훈"
                    countTripMembers(trip.id) shouldBe 2
                }
            }

            test("확정된 여행이나 빈자리가 없는 여행에 신규 참여하면 각각 409 TRIP_ALREADY_CONFIRMED와 TRIP_CAPACITY_FULL을 받고 등록되지 않는다") {
                // given
                val confirmedTrip = createTrip(memberCapacity = 3, status = "CONFIRMED")
                val fullTrip = createTrip(memberCapacity = 2)
                addGuest(fullTrip.id, "민수")
                val memberId = createMember("ACTIVE")

                // when
                val confirmedResponse = join(confirmedTrip.inviteCode, "지훈", accessToken(memberId))
                val fullResponse = join(fullTrip.inviteCode, "지훈", accessToken(memberId))

                // then
                assertSoftly {
                    confirmedResponse.statusCode() shouldBe 409
                    body(confirmedResponse)["code"] shouldBe "TRIP_ALREADY_CONFIRMED"
                    fullResponse.statusCode() shouldBe 409
                    body(fullResponse)["code"] shouldBe "TRIP_CAPACITY_FULL"
                    countMemberParticipations(memberId) shouldBe 0
                }
            }

            test("다른 참여자가 쓰는 닉네임으로 참여하면 로그인 참여자든 비로그인 참여자든 409 TRIP_NICKNAME_DUPLICATED를 받고 등록되지 않는다") {
                // given
                val trip = createTrip(memberCapacity = 4)
                addGuest(trip.id, "민수")
                val memberId = createMember("ACTIVE")

                // when
                val responses =
                    mapOf(
                        "관리자의 닉네임" to join(trip.inviteCode, ADMIN_NICKNAME, accessToken(memberId)),
                        "비로그인 참여자의 이름" to join(trip.inviteCode, "민수", accessToken(memberId)),
                    )

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe responses.mapValues { 409 }
                    responses.mapValues { body(it.value)["code"] } shouldBe responses.mapValues { "TRIP_NICKNAME_DUPLICATED" }
                    countMemberParticipations(memberId) shouldBe 0
                }
            }

            test("없는 코드, 형식이 틀린 코드와 삭제된 여행의 코드로 참여하면 404 TRIP_INVITATION_NOT_FOUND를 받는다") {
                // given
                val deletedTrip = createTrip(memberCapacity = 3, deleted = true)
                val memberId = createMember("ACTIVE")
                val codes =
                    mapOf(
                        "없는 코드" to newInviteCode(),
                        "형식이 틀린 코드" to "AB-12",
                        "NUL 문자가 든 코드" to "A\\u0000BC12",
                        "삭제된 여행의 코드" to deletedTrip.inviteCode,
                    )

                // when
                val responses = codes.mapValues { join(it.value, "지훈", accessToken(memberId)) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe responses.mapValues { 404 }
                    responses.mapValues { body(it.value)["code"] } shouldBe responses.mapValues { "TRIP_INVITATION_NOT_FOUND" }
                    countMemberParticipations(memberId) shouldBe 0
                }
            }

            test("초대 코드가 없거나 공백뿐이거나 닉네임 형식이 틀리면 400 COMMON_INVALID_REQUEST를 받고 등록되지 않는다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val memberId = createMember("ACTIVE")
                val requestBodies =
                    mapOf(
                        "초대 코드 없음" to """{"tripNickname": "지훈"}""",
                        "공백뿐인 초대 코드" to """{"inviteCode": "   ", "tripNickname": "지훈"}""",
                        "닉네임에 공백" to """{"inviteCode": "${trip.inviteCode}", "tripNickname": "지 훈"}""",
                        "11자 닉네임" to """{"inviteCode": "${trip.inviteCode}", "tripNickname": "${"가".repeat(11)}"}""",
                    )

                // when
                val responses = requestBodies.mapValues { post(it.value, accessToken(memberId)) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe responses.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe responses.mapValues { "COMMON_INVALID_REQUEST" }
                    body(responses.getValue("닉네임에 공백"))["errors"] shouldBe
                        listOf(mapOf("field" to "tripNickname", "reason" to "한글, 영문, 숫자로 1~10자여야 합니다."))
                    countMemberParticipations(memberId) shouldBe 0
                }
            }

            test("Access Token이 없거나 가입 전용 토큰이면 401 COMMON_UNAUTHORIZED를 받고 등록되지 않는다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val pendingMemberId = createMember("PENDING")

                // when
                val responses =
                    mapOf(
                        "Access Token 없음" to join(trip.inviteCode, "지훈", token = null),
                        "가입 전용 토큰" to join(trip.inviteCode, "지훈", authTokenCreator.createRegistrationToken(pendingMemberId)),
                    )

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe responses.mapValues { 401 }
                    responses.mapValues { body(it.value)["code"] } shouldBe responses.mapValues { "COMMON_UNAUTHORIZED" }
                    countTripMembers(trip.id) shouldBe 1
                }
            }
        }
    }

    private data class SavedTrip(
        val id: Long,
        val inviteCode: String,
        val adminMemberId: Long,
    )

    /** 관리자 한 명이 참여한 여행을 저장합니다. 테스트가 끝나면 여행과 참여자를 지웁니다. */
    private fun createTrip(
        memberCapacity: Int,
        status: String = "UNCONFIRMED",
        deleted: Boolean = false,
    ): SavedTrip {
        val inviteCode = newInviteCode()
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, confirmed_at, " +
                        "invite_code, idempotency_key, deleted_at, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', DATE '2026-11-05', DATE '2026-11-07', 'JP', 'JPY', ?, ?::trip_status, " +
                        "CASE WHEN ? = 'CONFIRMED' THEN now() END, ?, ?, CASE WHEN ? THEN now() END, now(), now()) RETURNING id",
                    Long::class.java,
                    memberCapacity,
                    status,
                    status,
                    inviteCode,
                    UUID.randomUUID(),
                    deleted,
                ),
            )
        tripIds.add(tripId)
        val adminMemberId = createMember("ACTIVE")
        jdbc.update(
            "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'ADMIN', now(), now())",
            tripId,
            adminMemberId,
            ADMIN_NICKNAME,
        )
        return SavedTrip(tripId, inviteCode, adminMemberId)
    }

    private fun addMember(
        tripId: Long,
        memberId: Long,
        tripNickname: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, joined_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, 'MEMBER', now(), now(), now()) RETURNING id",
                Long::class.java,
                tripId,
                memberId,
                tripNickname,
            ),
        )

    private fun addGuest(
        tripId: Long,
        name: String,
    ) {
        jdbc.update(
            "INSERT INTO trip_member (trip_id, trip_nickname, role, created_at, updated_at) VALUES (?, ?, 'MEMBER', now(), now())",
            tripId,
            name,
        )
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

    private fun accessToken(memberId: Long): String = authTokenCreator.createMemberTokens(memberId).accessToken

    /** 다른 여행과 겹치지 않을 가능성이 매우 높은 6자리 초대 코드입니다. */
    private fun newInviteCode(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(6)
            .uppercase()

    private fun findTripMember(
        tripId: Long,
        memberId: Long,
    ): Map<String, Any?>? =
        jdbc
            .queryForList(
                "SELECT id, trip_nickname, role::text AS role, joined_at FROM trip_member WHERE trip_id = ? AND member_id = ?",
                tripId,
                memberId,
            ).singleOrNull()

    private fun countTripMembers(tripId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM trip_member WHERE trip_id = ?", Int::class.java, tripId))

    private fun countMemberParticipations(memberId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM trip_member WHERE member_id = ?", Int::class.java, memberId))

    private fun join(
        inviteCode: String,
        tripNickname: String,
        token: String?,
    ): HttpResponse<String> = post("""{"inviteCode": "$inviteCode", "tripNickname": "$tripNickname"}""", token)

    private fun get(
        path: String,
        token: String?,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(
                    URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}$path"),
                ).GET()
        if (token != null) {
            request.header("Authorization", "Bearer $token")
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun search(
        code: String,
        token: String?,
    ): HttpResponse<String> = get("/api/v1/trip-invitations?code=${URLEncoder.encode(code, StandardCharsets.UTF_8)}", token)

    private fun post(
        requestBody: String,
        token: String?,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}/api/v1/trip-memberships"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        if (token != null) {
            request.header("Authorization", "Bearer $token")
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun body(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)

    companion object {
        private const val ADMIN_NICKNAME = "민지"
    }
}
