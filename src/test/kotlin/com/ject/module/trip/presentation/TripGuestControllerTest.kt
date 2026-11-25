package com.ject.module.trip.presentation

import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldBeNull
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
class TripGuestControllerTest : IntegrationTestSupport() {
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

        context("POST /api/v1/trips/{tripId}/guests") {
            test("관리자가 빈자리에 비로그인 참여자를 등록하면 201과 Location, 등록한 참여자를 받는다") {
                // given
                val trip = createTrip(memberCapacity = 3)

                // when
                val response =
                    send("POST", "/api/v1/trips/${trip.id}/guests", """{"tripNickname": "서연"}""", accessToken(trip.adminMemberId))

                // then
                val guest = findGuest(trip.id, "서연")
                assertSoftly {
                    response.statusCode() shouldBe 201
                    response.headers().firstValue("Location").orElse(null) shouldBe "/api/v1/trips/${trip.id}/guests/${guest["id"]}"
                    body(response) shouldBe mapOf("data" to mapOf("tripMemberId" to guest["id"].toString(), "tripNickname" to "서연"))
                    guest["member_id"].shouldBeNull()
                    guest["role"] shouldBe "MEMBER"
                    countRegisteredMembers(trip.id) shouldBe 2
                }
            }

            test("관리자가 아닌 참여자가 등록하면 403을 받고 참여자를 저장하지 않는다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val memberId = createMember()
                addMember(trip.id, memberId, "지훈")

                // when
                val response = send("POST", "/api/v1/trips/${trip.id}/guests", """{"tripNickname": "서연"}""", accessToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 403
                    body(response)["code"] shouldBe "COMMON_FORBIDDEN"
                    countRegisteredMembers(trip.id) shouldBe 2
                }
            }

            test("확정된 여행이면 409 TRIP_ALREADY_CONFIRMED, 빈자리가 없으면 409 TRIP_CAPACITY_FULL을 받는다") {
                // given
                val confirmedTrip = createTrip(memberCapacity = 2, status = "CONFIRMED")
                val fullTrip = createTrip(memberCapacity = 1)

                // when
                val confirmed =
                    send(
                        "POST",
                        "/api/v1/trips/${confirmedTrip.id}/guests",
                        """{"tripNickname": "서연"}""",
                        accessToken(confirmedTrip.adminMemberId),
                    )
                val full =
                    send("POST", "/api/v1/trips/${fullTrip.id}/guests", """{"tripNickname": "서연"}""", accessToken(fullTrip.adminMemberId))

                // then
                assertSoftly {
                    confirmed.statusCode() shouldBe 409
                    body(confirmed)["code"] shouldBe "TRIP_ALREADY_CONFIRMED"
                    full.statusCode() shouldBe 409
                    body(full)["code"] shouldBe "TRIP_CAPACITY_FULL"
                }
            }

            test("이름이 같은 여행의 로그인 참여자 닉네임과 겹치면 409 TRIP_NICKNAME_DUPLICATED를 받는다") {
                // given
                val trip = createTrip(memberCapacity = 3)

                // when
                val response =
                    send(
                        "POST",
                        "/api/v1/trips/${trip.id}/guests",
                        """{"tripNickname": "$ADMIN_NICKNAME"}""",
                        accessToken(trip.adminMemberId),
                    )

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    body(response)["code"] shouldBe "TRIP_NICKNAME_DUPLICATED"
                    countRegisteredMembers(trip.id) shouldBe 1
                }
            }

            test("이름이 형식을 벗어나면 400 COMMON_INVALID_REQUEST를 받는다") {
                // given
                val trip = createTrip(memberCapacity = 3)

                // when
                val response =
                    send("POST", "/api/v1/trips/${trip.id}/guests", """{"tripNickname": "서 연"}""", accessToken(trip.adminMemberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 400
                    body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                }
            }
        }

        context("PATCH /api/v1/trips/{tripId}/guests/{tripMemberId}") {
            test("확정된 여행에서도 관리자가 비로그인 참여자의 이름을 바꾸면 200과 바뀐 이름을 받는다") {
                // given
                val trip = createTrip(memberCapacity = 2, status = "CONFIRMED")
                val guestId = addGuest(trip.id, "서연")

                // when
                val response =
                    send("PATCH", "/api/v1/trips/${trip.id}/guests/$guestId", """{"tripNickname": "하린"}""", accessToken(trip.adminMemberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe mapOf("data" to mapOf("tripMemberId" to guestId.toString(), "tripNickname" to "하린"))
                    findTripNickname(guestId) shouldBe "하린"
                }
            }

            test("바꿀 이름이 다른 참여자와 겹치면 409 TRIP_NICKNAME_DUPLICATED를 받고 이름은 그대로다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val guestId = addGuest(trip.id, "서연")
                addGuest(trip.id, "하린")

                // when
                val response =
                    send("PATCH", "/api/v1/trips/${trip.id}/guests/$guestId", """{"tripNickname": "하린"}""", accessToken(trip.adminMemberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    body(response)["code"] shouldBe "TRIP_NICKNAME_DUPLICATED"
                    findTripNickname(guestId) shouldBe "서연"
                }
            }

            test("대상이 로그인 참여자이면 404를 받는다") {
                // given
                val trip = createTrip(memberCapacity = 3)
                val memberTripMemberId = addMember(trip.id, createMember(), "지훈")

                // when
                val response =
                    send(
                        "PATCH",
                        "/api/v1/trips/${trip.id}/guests/$memberTripMemberId",
                        """{"tripNickname": "하린"}""",
                        accessToken(trip.adminMemberId),
                    )

                // then
                assertSoftly {
                    response.statusCode() shouldBe 404
                    findTripNickname(memberTripMemberId) shouldBe "지훈"
                }
            }
        }

        context("DELETE /api/v1/trips/{tripId}/guests/{tripMemberId}") {
            test("정원이 찬 여행에서 비로그인 참여자를 지우면 204를 받고 등록 인원만 한 명 준다") {
                // given
                val trip = createTrip(memberCapacity = 2)
                val guestId = addGuest(trip.id, "서연")

                // when
                val response = send("DELETE", "/api/v1/trips/${trip.id}/guests/$guestId", null, accessToken(trip.adminMemberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 204
                    countRegisteredMembers(trip.id) shouldBe 1
                    findMemberCapacity(trip.id) shouldBe 2
                }
            }

            test("이미 지운 비로그인 참여자를 다시 지우면 404를 받는다") {
                // given
                val trip = createTrip(memberCapacity = 2)
                val guestId = addGuest(trip.id, "서연")
                send("DELETE", "/api/v1/trips/${trip.id}/guests/$guestId", null, accessToken(trip.adminMemberId))

                // when
                val response = send("DELETE", "/api/v1/trips/${trip.id}/guests/$guestId", null, accessToken(trip.adminMemberId))

                // then
                response.statusCode() shouldBe 404
            }

            test("확정된 여행의 비로그인 참여자를 지우면 409 TRIP_ALREADY_CONFIRMED를 받고 참여자는 남는다") {
                // given
                val trip = createTrip(memberCapacity = 2, status = "CONFIRMED")
                val guestId = addGuest(trip.id, "서연")

                // when
                val response = send("DELETE", "/api/v1/trips/${trip.id}/guests/$guestId", null, accessToken(trip.adminMemberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    body(response)["code"] shouldBe "TRIP_ALREADY_CONFIRMED"
                    countRegisteredMembers(trip.id) shouldBe 2
                }
            }
        }
    }

    private data class SavedTrip(
        val id: Long,
        val adminMemberId: Long,
    )

    /** 관리자 한 명이 참여한 여행을 저장합니다. 테스트가 끝나면 여행과 참여자를 지웁니다. */
    private fun createTrip(
        memberCapacity: Int,
        status: String = "UNCONFIRMED",
    ): SavedTrip {
        val tripId =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO trip (name, start_date, end_date, country_code, base_currency, member_capacity, status, confirmed_at, " +
                        "invite_code, idempotency_key, created_at, updated_at) " +
                        "VALUES ('도쿄 여행', DATE '2026-11-05', DATE '2026-11-07', 'JP', 'JPY', ?, ?::trip_status, " +
                        "CASE WHEN ? = 'CONFIRMED' THEN now() END, ?, ?, now(), now()) RETURNING id",
                    Long::class.java,
                    memberCapacity,
                    status,
                    status,
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(6)
                        .uppercase(),
                    UUID.randomUUID(),
                ),
            )
        tripIds.add(tripId)
        val adminMemberId = createMember()
        jdbc.update(
            "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'ADMIN', 'MEMBER', now(), now())",
            tripId,
            adminMemberId,
            ADMIN_NICKNAME,
        )
        return SavedTrip(tripId, adminMemberId)
    }

    private fun addMember(
        tripId: Long,
        memberId: Long,
        tripNickname: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, member_id, trip_nickname, role, joined_at, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, 'MEMBER', now(), 'MEMBER', now(), now()) RETURNING id",
                Long::class.java,
                tripId,
                memberId,
                tripNickname,
            ),
        )

    private fun addGuest(
        tripId: Long,
        tripNickname: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO trip_member (trip_id, trip_nickname, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, 'MEMBER', 'GUEST', now(), now()) RETURNING id",
                Long::class.java,
                tripId,
                tripNickname,
            ),
        )

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

    private fun accessToken(memberId: Long): String = authTokenCreator.createMemberTokens(memberId).accessToken

    private fun findGuest(
        tripId: Long,
        tripNickname: String,
    ): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT id, member_id, role::text AS role FROM trip_member WHERE trip_id = ? AND trip_nickname = ? AND status = 'GUEST'",
            tripId,
            tripNickname,
        )

    private fun findTripNickname(tripMemberId: Long): String? =
        jdbc.queryForObject("SELECT trip_nickname FROM trip_member WHERE id = ?", String::class.java, tripMemberId)

    private fun countRegisteredMembers(tripId: Long): Int =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM trip_member WHERE trip_id = ? AND status IN ('MEMBER', 'GUEST')",
                Int::class.java,
                tripId,
            ),
        )

    private fun findMemberCapacity(tripId: Long): Int? =
        jdbc.queryForObject("SELECT member_capacity FROM trip WHERE id = ?", Int::class.java, tripId)

    private fun send(
        method: String,
        path: String,
        requestBody: String?,
        token: String,
    ): HttpResponse<String> {
        val publisher = if (requestBody == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(requestBody)
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}$path"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .method(method, publisher)
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun body(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)

    companion object {
        private const val ADMIN_NICKNAME = "민지"
    }
}
