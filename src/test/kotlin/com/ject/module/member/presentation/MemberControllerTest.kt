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
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
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

        context("POST /api/v1/me/registration") {
            test("필수 약관에 동의한 가입 완료 요청은 200과 회원용 Access Token, Refresh Token만 반환한다") {
                // given
                val memberId = createMember("PENDING")
                val termsId = createPolicy("TERMS", "v1", isRequired = true, effectiveAt = "now() - interval '1 day'")
                val requestBody =
                    """
                    {
                      "agreements": [{"termsId": "$termsId", "isAgreed": true}],
                      "platform": "IOS",
                      "appVersion": "1.0.0",
                      "isAgeEligible": true,
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
                        """{"agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true,"survey":{"gender":"MALE"}}""",
                        """{"agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true,"survey":{}}""",
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
                    """{"agreements":[{"termsId":"$termsId","isAgreed":false}],"platform":"ANDROID","appVersion":"1.0.0","isAgeEligible":true}"""

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

            test("닉네임 없이 보내도 200으로 가입을 완료한다") {
                // given
                val memberId = createMember("PENDING")
                val requestBody = """{"agreements":[],"platform":"ANDROID","appVersion":"1.0.0","isAgeEligible":true}"""

                // when
                val response = post(REGISTRATION_PATH, requestBody, authTokenCreator.createRegistrationToken(memberId))

                // then
                assertSoftly {
                    response.statusCode() shouldBe 200
                    findStatus(memberId) shouldBe "ACTIVE"
                }
            }

            test("형식이 틀린 가입 완료 요청은 400 COMMON_INVALID_REQUEST를 반환하고 회원은 가입 중으로 남는다") {
                // given
                val memberId = createMember("PENDING")
                val registrationToken = authTokenCreator.createRegistrationToken(memberId)
                val requestBodies =
                    mapOf(
                        "가입 때 받지 않는 닉네임" to
                            """{"nickname":"빌릿","agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true}""",
                        "약관 동의 목록 누락" to """{"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true}""",
                        "동의 기기 누락" to """{"agreements":[],"appVersion":"1.0.0","isAgeEligible":true}""",
                        "허용하지 않는 동의 기기" to
                            """{"agreements":[],"platform":"WEB","appVersion":"1.0.0","isAgeEligible":true}""",
                        "동의 여부 누락" to
                            """{"agreements":[{"termsId":"1"}],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true}""",
                        "숫자가 아닌 약관 식별자" to
                            """{"agreements":[{"termsId":"terms","isAgreed":true}],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true}""",
                        "허용하지 않는 성별" to
                            """{"agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true,"survey":{"gender":"OTHER"}}""",
                        "불편 사항 3개" to
                            """{"agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true,""" +
                            """"survey":{"settlementPainPoints":["SHARE_CALCULATION","PAYMENT_HISTORY","ETC"]}}""",
                        "불편 사항 중복" to
                            """{"agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true,""" +
                            """"survey":{"settlementPainPoints":["ETC","ETC"]}}""",
                        "약관 동의 목록에 null" to
                            """{"agreements":[null],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true}""",
                        "공백뿐인 기타 입력" to
                            """{"agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true,""" +
                            """"survey":{"settlementPainPoints":["ETC"],"otherSettlementPainPoint":" "}}""",
                        "기타 없이 기타 입력" to
                            """{"agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true,""" +
                            """"survey":{"settlementPainPoints":["PAYMENT_HISTORY"],"otherSettlementPainPoint":"환율 계산"}}""",
                        "앱 버전 누락" to
                            """{"agreements":[],"platform":"IOS","isAgeEligible":true}""",
                        "공백뿐인 앱 버전" to
                            """{"agreements":[],"platform":"IOS","appVersion":" ","isAgeEligible":true}""",
                        "앱 버전 21자" to
                            """{"agreements":[],"platform":"IOS","appVersion":"${"1".repeat(
                                21,
                            )}","isAgeEligible":true}""",
                        "만 18세 이상 확인 누락" to
                            """{"agreements":[],"platform":"IOS","appVersion":"1.0.0"}""",
                        "만 18세 미만" to
                            """{"agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":false}""",
                        "명세에 없는 필드" to
                            """{"agreements":[],"platform":"IOS","appVersion":"1.0.0","isAgeEligible":true,"name":"김빌릿"}""",
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
    }
}
