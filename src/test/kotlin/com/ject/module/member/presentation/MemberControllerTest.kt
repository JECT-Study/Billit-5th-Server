package com.ject.module.member.presentation

import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.jwt.JwtDecoder
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemberControllerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    @Autowired
    @Qualifier("memberJwtDecoder")
    private lateinit var memberJwtDecoder: JwtDecoder

    private val httpClient = HttpClient.newHttpClient()
    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member_consent WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member_pain_point WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
            jdbc.update("DELETE FROM policy")
        }

        context("GET /api/v1/me/registration") {
            test("가입 중 회원은 200과 가입 상태, 소셜 표시 이름, 시행 중인 가입 약관의 현재 버전을 받는다") {
                // given
                val memberId = createMember("PENDING", socialDisplayName = "김빌릿")
                createPolicy("TERMS", "v1", isRequired = true, effectiveAt = "now() - interval '2 days'")
                val termsId = createPolicy("TERMS", "v2", isRequired = true, effectiveAt = "now() - interval '1 day'")
                createPolicy("TERMS", "v3", isRequired = true, effectiveAt = "now() + interval '1 day'")
                val marketingId = createPolicy("MARKETING", "v1", isRequired = false, effectiveAt = "now() - interval '1 day'")
                createPolicy("OCR", "v1", isRequired = false, effectiveAt = "now() - interval '1 day'")

                // when
                val response = get(REGISTRATION_PATH, authTokenCreator.createRegistrationToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe
                        mapOf(
                            "data" to
                                mapOf(
                                    "status" to "SIGNING_UP",
                                    "socialDisplayName" to "김빌릿",
                                    "terms" to
                                        listOf(
                                            mapOf(
                                                "termsId" to termsId.toString(),
                                                "title" to "TERMS v2",
                                                "isRequired" to true,
                                                "contentUrl" to "https://terms.billit.test/terms/terms/v2",
                                            ),
                                            mapOf(
                                                "termsId" to marketingId.toString(),
                                                "title" to "MARKETING v1",
                                                "isRequired" to false,
                                                "contentUrl" to "https://terms.billit.test/terms/marketing/v1",
                                            ),
                                        ),
                                    "name" to null,
                                    "age" to null,
                                ),
                        )
                }
            }

            test("가입을 마친 회원의 가입 전용 토큰이면 403 COMMON_FORBIDDEN을 반환한다") {
                // given
                val memberId = createMember("ACTIVE")

                // when
                val response = get(REGISTRATION_PATH, authTokenCreator.createRegistrationToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 403
                    body(response)["code"] shouldBe "COMMON_FORBIDDEN"
                }
            }

            test("가입 전용 토큰의 회원이 없으면 401 COMMON_UNAUTHORIZED를 반환한다") {
                // given
                val memberId = createMember("PENDING")
                val registrationToken = authTokenCreator.createRegistrationToken(memberId)
                jdbc.update("DELETE FROM member WHERE id = ?", memberId)

                // when
                val response = get(REGISTRATION_PATH, registrationToken)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 401
                    body(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
                }
            }
        }

        context("GET /api/v1/nickname-availability") {
            test("아무도 쓰지 않는 닉네임은 200과 isAvailable true를 반환한다") {
                // given
                val registrationToken = authTokenCreator.createRegistrationToken(createMember("PENDING"))
                val nickname = newNickname()

                // when
                val response = get(nicknamePath(nickname), registrationToken)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe mapOf("data" to mapOf("nickname" to nickname, "isAvailable" to true))
                }
            }

            test("다른 회원이 쓰는 닉네임은 200과 isAvailable false를 반환한다") {
                // given
                val registrationToken = authTokenCreator.createRegistrationToken(createMember("PENDING"))
                val nickname = newNickname()
                createMember("ACTIVE", nickname = nickname)

                // when
                val response = get(nicknamePath(nickname), registrationToken)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    body(response) shouldBe mapOf("data" to mapOf("nickname" to nickname, "isAvailable" to false))
                }
            }

            test("11자, 공백 포함, 한글 자모, 빈 닉네임과 닉네임 누락은 400 COMMON_INVALID_REQUEST를 반환한다") {
                // given
                val registrationToken = authTokenCreator.createRegistrationToken(createMember("PENDING"))
                val paths =
                    listOf(
                        nicknamePath("가나다라마바사아자차카"),
                        nicknamePath("빌릿 "),
                        nicknamePath("ㅂㅣㄹ"),
                        nicknamePath(""),
                        NICKNAME_AVAILABILITY_PATH,
                    )

                // when
                val responses = paths.map { get(it, registrationToken) }

                // then
                assertSoftly {
                    responses.map { it.statusCode() } shouldBe listOf(400, 400, 400, 400, 400)
                    responses.map { body(it)["code"] }.toSet() shouldBe setOf("COMMON_INVALID_REQUEST")
                }
            }

            test("가입을 마친 회원의 가입 전용 토큰이면 403 COMMON_FORBIDDEN을 반환한다") {
                // given
                val registrationToken = authTokenCreator.createRegistrationToken(createMember("ACTIVE"))

                // when
                val response = get(nicknamePath(newNickname()), registrationToken)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 403
                    body(response)["code"] shouldBe "COMMON_FORBIDDEN"
                }
            }

            test("형식이 틀린 닉네임은 오류 필드로 nickname을 알려준다") {
                // given
                val registrationToken = authTokenCreator.createRegistrationToken(createMember("PENDING"))

                // when
                val response = get(nicknamePath("빌릿!"), registrationToken)

                // then
                val errors = body(response)["errors"] as List<*>
                assertSoftly {
                    response.statusCode() shouldBe 400
                    errors.map { (it as Map<*, *>)["field"] } shouldBe listOf("nickname")
                }
            }

            test("한글 완성형, 영문과 숫자를 섞은 10자 닉네임은 200을 반환한다") {
                // given
                val registrationToken = authTokenCreator.createRegistrationToken(createMember("PENDING"))

                // when
                val response = get(nicknamePath("빌릿Abc12345"), registrationToken)

                // then
                response.statusCode() shouldBe 200
            }
        }

        context("POST /api/v1/me/registration") {
            test("필수 약관에 동의한 가입 완료 요청은 200과 회원용 Access Token, Refresh Token만 반환한다") {
                // given
                val memberId = createMember("PENDING")
                val termsId = createPolicy("TERMS", "v1", isRequired = true, effectiveAt = "now() - interval '1 day'")
                val nickname = newNickname()
                val requestBody =
                    """
                    {
                      "nickname": "$nickname",
                      "agreements": [{"termsId": "$termsId", "isAgreed": true}],
                      "platform": "IOS",
                      "survey": {
                        "gender": "MALE",
                        "isTripOrganizer": false,
                        "settlementPainPoints": ["ETC"],
                        "otherSettlementPainPoint": "환율 계산"
                      }
                    }
                    """.trimIndent()

                // when
                val response = post(REGISTRATION_PATH, requestBody, authTokenCreator.createRegistrationToken(memberId))

                // then
                val data = body(response)["data"] as Map<*, *>
                assertSoftly {
                    response.statusCode() shouldBe 200
                    data.keys shouldBe setOf("accessToken", "refreshToken")
                    memberJwtDecoder.decode(data["accessToken"] as String).subject shouldBe memberId.toString()
                    (data["refreshToken"] as String).shouldNotBeBlank()
                    findStatus(memberId) shouldBe "ACTIVE"
                }
            }

            test("설문에서 응답한 항목만 보내도 200으로 가입을 완료한다") {
                // given
                val memberId = createMember("PENDING")
                val requestBodies =
                    listOf(
                        """{"nickname":"${newNickname()}","agreements":[],"platform":"IOS","survey":{"gender":"MALE"}}""",
                        """{"nickname":"${newNickname()}","agreements":[],"platform":"IOS","survey":{}}""",
                    )
                val otherMemberId = createMember("PENDING")

                // when
                val responses =
                    listOf(
                        post(REGISTRATION_PATH, requestBodies[0], authTokenCreator.createRegistrationToken(memberId)),
                        post(REGISTRATION_PATH, requestBodies[1], authTokenCreator.createRegistrationToken(otherMemberId)),
                    )

                // then
                assertSoftly {
                    responses.map { it.statusCode() } shouldBe listOf(200, 200)
                    findStatus(memberId) shouldBe "ACTIVE"
                    findStatus(otherMemberId) shouldBe "ACTIVE"
                }
            }

            test("필수 약관에 동의하지 않으면 400 USER_REQUIRED_AGREEMENT_MISSING을 반환한다") {
                // given
                val memberId = createMember("PENDING")
                val termsId = createPolicy("TERMS", "v1", isRequired = true, effectiveAt = "now() - interval '1 day'")
                val requestBody =
                    """{"nickname":"${newNickname()}","agreements":[{"termsId":"$termsId","isAgreed":false}],"platform":"ANDROID"}"""

                // when
                val response = post(REGISTRATION_PATH, requestBody, authTokenCreator.createRegistrationToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 400
                    body(response) shouldBe
                        mapOf("code" to "USER_REQUIRED_AGREEMENT_MISSING", "message" to "필수 약관에 동의해주세요.", "errors" to null)
                    findStatus(memberId) shouldBe "PENDING"
                }
            }

            test("다른 회원이 쓰는 닉네임이면 409 USER_NICKNAME_ALREADY_USED를 반환한다") {
                // given
                val nickname = newNickname()
                createMember("ACTIVE", nickname = nickname)
                val memberId = createMember("PENDING")
                val requestBody = """{"nickname":"$nickname","agreements":[],"platform":"ANDROID"}"""

                // when
                val response = post(REGISTRATION_PATH, requestBody, authTokenCreator.createRegistrationToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 409
                    body(response) shouldBe
                        mapOf("code" to "USER_NICKNAME_ALREADY_USED", "message" to "이미 사용 중인 닉네임입니다.", "errors" to null)
                    findStatus(memberId) shouldBe "PENDING"
                }
            }

            test("형식이 틀린 가입 완료 요청은 400 COMMON_INVALID_REQUEST를 반환하고 회원은 가입 중으로 남는다") {
                // given
                val memberId = createMember("PENDING")
                val registrationToken = authTokenCreator.createRegistrationToken(memberId)
                val nickname = newNickname()
                val requestBodies =
                    mapOf(
                        "닉네임에 공백" to """{"nickname":"빌릿 ","agreements":[],"platform":"IOS"}""",
                        "닉네임 11자" to """{"nickname":"가나다라마바사아자차카","agreements":[],"platform":"IOS"}""",
                        "닉네임 누락" to """{"agreements":[],"platform":"IOS"}""",
                        "약관 동의 목록 누락" to """{"nickname":"$nickname","platform":"IOS"}""",
                        "동의 기기 누락" to """{"nickname":"$nickname","agreements":[]}""",
                        "허용하지 않는 동의 기기" to """{"nickname":"$nickname","agreements":[],"platform":"WEB"}""",
                        "동의 여부 누락" to """{"nickname":"$nickname","agreements":[{"termsId":"1"}],"platform":"IOS"}""",
                        "숫자가 아닌 약관 식별자" to
                            """{"nickname":"$nickname","agreements":[{"termsId":"terms","isAgreed":true}],"platform":"IOS"}""",
                        "허용하지 않는 성별" to """{"nickname":"$nickname","agreements":[],"platform":"IOS","survey":{"gender":"OTHER"}}""",
                        "불편 사항 3개" to
                            """{"nickname":"$nickname","agreements":[],"platform":"IOS",""" +
                            """"survey":{"settlementPainPoints":["SHARE_CALCULATION","PAYMENT_HISTORY","ETC"]}}""",
                        "불편 사항 중복" to
                            """{"nickname":"$nickname","agreements":[],"platform":"IOS",""" +
                            """"survey":{"settlementPainPoints":["ETC","ETC"]}}""",
                        "닉네임 null" to """{"nickname":null,"agreements":[],"platform":"IOS"}""",
                        "약관 동의 목록에 null" to """{"nickname":"$nickname","agreements":[null],"platform":"IOS"}""",
                        "공백뿐인 기타 입력" to
                            """{"nickname":"$nickname","agreements":[],"platform":"IOS",""" +
                            """"survey":{"settlementPainPoints":["ETC"],"otherSettlementPainPoint":" "}}""",
                        "기타 없이 기타 입력" to
                            """{"nickname":"$nickname","agreements":[],"platform":"IOS",""" +
                            """"survey":{"settlementPainPoints":["PAYMENT_HISTORY"],"otherSettlementPainPoint":"환율 계산"}}""",
                        "명세에 없는 필드" to """{"nickname":"$nickname","agreements":[],"platform":"IOS","name":"김빌릿"}""",
                    )

                // when
                val responses = requestBodies.mapValues { post(REGISTRATION_PATH, it.value, registrationToken) }

                // then
                assertSoftly {
                    responses.mapValues { it.value.statusCode() } shouldBe requestBodies.mapValues { 400 }
                    responses.mapValues { body(it.value)["code"] } shouldBe requestBodies.mapValues { "COMMON_INVALID_REQUEST" }
                    findStatus(memberId) shouldBe "PENDING"
                }
            }
        }
    }

    private fun newNickname(): String =
        UUID
            .randomUUID()
            .toString()
            .replace("-", "")
            .take(10)

    private fun nicknamePath(nickname: String): String =
        "$NICKNAME_AVAILABILITY_PATH?nickname=${URLEncoder.encode(nickname, StandardCharsets.UTF_8)}"

    private fun createMember(
        status: String,
        nickname: String? = null,
        socialDisplayName: String? = null,
    ): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, nickname, social_display_name, status, created_at, updated_at) " +
                        "VALUES ('GOOGLE', ?, ?, ?::member_status, now(), now()) RETURNING id",
                    Long::class.java,
                    nickname,
                    socialDisplayName,
                    status,
                ),
            )
        memberIds.add(id)
        return id
    }

    /** [effectiveAt]은 시행 시각을 구하는 SQL 식입니다. */
    private fun createPolicy(
        type: String,
        version: String,
        isRequired: Boolean,
        effectiveAt: String,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO policy (type, version, title, content, is_required, effective_at, created_at) " +
                    "VALUES (?::policy_type, ?, ?, '약관 전문', ?, $effectiveAt, now()) RETURNING id",
                Long::class.java,
                type,
                version,
                "$type $version",
                isRequired,
            ),
        )

    private fun findStatus(memberId: Long): String? =
        jdbc.queryForObject("SELECT status::text FROM member WHERE id = ?", String::class.java, memberId)

    private fun get(
        path: String,
        token: String,
    ): HttpResponse<String> = httpClient.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(
        path: String,
        requestBody: String,
        token: String,
    ): HttpResponse<String> =
        httpClient.send(
            request(path, token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun request(
        path: String,
        token: String,
    ): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}$path"))
            .header("Authorization", "Bearer $token")

    private fun body(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)

    companion object {
        private const val REGISTRATION_PATH = "/api/v1/me/registration"
        private const val NICKNAME_AVAILABILITY_PATH = "/api/v1/nickname-availability"
    }
}
