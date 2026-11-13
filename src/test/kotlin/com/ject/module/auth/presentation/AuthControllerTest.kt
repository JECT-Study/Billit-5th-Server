package com.ject.module.auth.presentation

import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.support.IntegrationTestSupport
import com.ject.support.SocialIdTokenFixture
import com.ject.support.SocialIdTokenFixture.Companion.GOOGLE_ISSUER
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    private val httpClient = HttpClient.newHttpClient()
    private val providerUserIds = mutableListOf<String>()

    init {
        afterEach {
            providerUserIds.forEach { providerUserId ->
                jdbc.update(
                    "DELETE FROM auth_session WHERE member_id IN (SELECT id FROM member WHERE social_provider_user_id = ?)",
                    providerUserId,
                )
                jdbc.update("DELETE FROM member WHERE social_provider_user_id = ?", providerUserId)
            }
            providerUserIds.clear()
        }

        context("POST /api/v1/auth/social-logins") {
            test("처음 로그인한 구글 계정은 200과 REGISTRATION_REQUIRED, 가입 전용 토큰만 반환한다") {
                // given
                val providerUserId = newProviderUserId()
                val idToken = fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, providerUserId, Instant.now().plusSeconds(600))

                // when
                val response = post(SOCIAL_LOGIN_PATH, """{"provider":"GOOGLE","idToken":"$idToken"}""")

                // then
                val data = body(response)["data"] as Map<*, *>
                assertSoftly {
                    response.statusCode() shouldBe 200
                    data.keys shouldBe setOf("status", "registrationToken")
                    data["status"] shouldBe "REGISTRATION_REQUIRED"
                    (data["registrationToken"] as String).shouldNotBeBlank()
                }
            }

            test("가입을 마친 구글 회원은 200과 AUTHENTICATED, Access Token과 Refresh Token만 반환한다") {
                // given
                val providerUserId = newProviderUserId()
                val memberId =
                    jdbc.queryForObject(
                        "INSERT INTO member (social_provider, social_provider_user_id, status, created_at, updated_at) " +
                            "VALUES ('GOOGLE', ?, 'ACTIVE', now(), now()) RETURNING id",
                        Long::class.java,
                        providerUserId,
                    )
                val idToken = fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, providerUserId, Instant.now().plusSeconds(600))

                // when
                val response = post(SOCIAL_LOGIN_PATH, """{"provider":"GOOGLE","idToken":"$idToken"}""")

                // then
                val data = body(response)["data"] as Map<*, *>
                val accessClaims = SignedJWT.parse(data["accessToken"] as String).jwtClaimsSet
                val refreshClaims = SignedJWT.parse(data["refreshToken"] as String).jwtClaimsSet
                assertSoftly {
                    response.statusCode() shouldBe 200
                    data.keys shouldBe setOf("status", "accessToken", "refreshToken")
                    data["status"] shouldBe "AUTHENTICATED"
                    accessClaims.subject shouldBe memberId.toString()
                    accessClaims.getStringClaim("token_type") shouldBe "ACCESS"
                    refreshClaims.subject shouldBe memberId.toString()
                    refreshClaims.getStringClaim("token_type") shouldBe "REFRESH"
                }
            }

            test("aud가 다른 ID 토큰은 401 AUTH_SOCIAL_AUTHENTICATION_FAILED를 반환하고 회원을 만들지 않는다") {
                // given
                val providerUserId = newProviderUserId()
                val idToken = fixture.idToken(GOOGLE_ISSUER, "other-client-id", providerUserId, Instant.now().plusSeconds(600))

                // when
                val response = post(SOCIAL_LOGIN_PATH, """{"provider":"GOOGLE","idToken":"$idToken"}""")

                // then
                assertSoftly {
                    response.statusCode() shouldBe 401
                    body(response) shouldBe
                        mapOf("code" to "AUTH_SOCIAL_AUTHENTICATION_FAILED", "message" to "소셜 인증에 실패했습니다.", "errors" to null)
                    countMembers(providerUserId) shouldBe 0
                }
            }

            test("제공자 JWKS에 연결할 수 없으면 503 AUTH_SOCIAL_PROVIDER_UNAVAILABLE을 반환한다") {
                // given
                val idToken = fixture.idToken(KAKAO_ISSUER, KAKAO_APP_KEY, newProviderUserId(), Instant.now().plusSeconds(600))

                // when
                val response = post(SOCIAL_LOGIN_PATH, """{"provider":"KAKAO","idToken":"$idToken"}""")

                // then
                assertSoftly {
                    response.statusCode() shouldBe 503
                    body(response) shouldBe
                        mapOf(
                            "code" to "AUTH_SOCIAL_PROVIDER_UNAVAILABLE",
                            "message" to "소셜 로그인 제공자에 연결할 수 없습니다.",
                            "errors" to null,
                        )
                }
            }

            val invalidBodies =
                mapOf(
                    "provider 누락" to """{"idToken":"token"}""",
                    "idToken 누락" to """{"provider":"GOOGLE"}""",
                    "idToken이 공백" to """{"provider":"GOOGLE","idToken":"  "}""",
                    "provider가 null" to """{"provider":null,"idToken":"token"}""",
                    "지원하지 않는 provider" to """{"provider":"NAVER","idToken":"token"}""",
                    "provider가 숫자" to """{"provider":0,"idToken":"token"}""",
                    "인가 코드가 필요한 APPLE" to """{"provider":"APPLE","idToken":"token"}""",
                    "정의하지 않은 필드" to """{"provider":"GOOGLE","idToken":"token","nonce":"n"}""",
                    "JSON이 아님" to "provider=GOOGLE",
                )
            for ((case, requestBody) in invalidBodies) {
                test("요청 형식 오류($case)는 400 COMMON_INVALID_REQUEST를 반환한다") {
                    // given
                    val path = SOCIAL_LOGIN_PATH

                    // when
                    val response = post(path, requestBody)

                    // then
                    response.statusCode() shouldBe 400
                    body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                }
            }
        }
        context("POST /api/v1/auth/token-refreshes") {
            test("유효한 Refresh Token이면 200과 새 Access Token, Refresh Token만 반환한다") {
                // given
                val issued = authTokenCreator.createMemberTokens(createActiveMember())

                // when
                val response = post(TOKEN_REFRESH_PATH, """{"refreshToken":"${issued.refreshToken}"}""")

                // then
                val data = body(response)["data"] as Map<*, *>
                assertSoftly {
                    response.statusCode() shouldBe 200
                    data.keys shouldBe setOf("accessToken", "refreshToken")
                    data["refreshToken"] shouldNotBe issued.refreshToken
                }
            }

            test("유효하지 않은 Refresh Token이면 401 AUTH_REFRESH_TOKEN_INVALID를 반환한다") {
                // given
                val requestBody = """{"refreshToken":"invalid-token"}"""

                // when
                val response = post(TOKEN_REFRESH_PATH, requestBody)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 401
                    body(response) shouldBe
                        mapOf("code" to "AUTH_REFRESH_TOKEN_INVALID", "message" to "Refresh Token이 유효하지 않습니다.", "errors" to null)
                }
            }

            val invalidBodies =
                mapOf(
                    "refreshToken 누락" to "{}",
                    "refreshToken이 빈 문자열" to """{"refreshToken":""}""",
                    "refreshToken이 null" to """{"refreshToken":null}""",
                    "정의하지 않은 필드" to """{"refreshToken":"token","deviceId":"d"}""",
                )
            for ((case, requestBody) in invalidBodies) {
                test("요청 형식 오류($case)는 400 COMMON_INVALID_REQUEST를 반환한다") {
                    // given
                    val path = TOKEN_REFRESH_PATH

                    // when
                    val response = post(path, requestBody)

                    // then
                    response.statusCode() shouldBe 400
                    body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                }
            }
        }
        context("POST /api/v1/auth/logout") {
            test("Access Token으로 본문 없이 로그아웃하면 204를 반환하고 이후 그 Access Token은 401이다") {
                // given
                val tokens = authTokenCreator.createMemberTokens(createActiveMember())

                // when
                val response = post(LOGOUT_PATH, requestBody = null, accessToken = tokens.accessToken)

                // then
                val afterLogout = post(LOGOUT_PATH, requestBody = null, accessToken = tokens.accessToken)
                assertSoftly {
                    response.statusCode() shouldBe 204
                    response.body() shouldBe ""
                    afterLogout.statusCode() shouldBe 401
                    body(afterLogout)["code"] shouldBe "COMMON_UNAUTHORIZED"
                }
            }

            test("헤더 없이 본문의 Refresh Token으로 로그아웃하면 204를 반환하고 그 세션의 두 토큰은 401이다") {
                // given
                val tokens = authTokenCreator.createMemberTokens(createActiveMember())

                // when
                val response = post(LOGOUT_PATH, """{"refreshToken":"${tokens.refreshToken}"}""")

                // then
                val refreshAfterLogout = post(TOKEN_REFRESH_PATH, """{"refreshToken":"${tokens.refreshToken}"}""")
                val accessAfterLogout = post(LOGOUT_PATH, requestBody = null, accessToken = tokens.accessToken)
                assertSoftly {
                    response.statusCode() shouldBe 204
                    refreshAfterLogout.statusCode() shouldBe 401
                    body(refreshAfterLogout)["code"] shouldBe "AUTH_REFRESH_TOKEN_INVALID"
                    accessAfterLogout.statusCode() shouldBe 401
                }
            }

            test("로그아웃해도 같은 회원의 다른 세션은 유지한다") {
                // given
                val memberId = createActiveMember()
                val current = authTokenCreator.createMemberTokens(memberId)
                val other = authTokenCreator.createMemberTokens(memberId)

                // when
                post(LOGOUT_PATH, requestBody = null, accessToken = current.accessToken)

                // then
                val otherRefresh = post(TOKEN_REFRESH_PATH, """{"refreshToken":"${other.refreshToken}"}""")
                otherRefresh.statusCode() shouldBe 200
            }

            test("이미 종료된 세션의 Refresh Token으로 로그아웃하면 401 COMMON_UNAUTHORIZED를 반환한다") {
                // given
                val tokens = authTokenCreator.createMemberTokens(createActiveMember())
                post(LOGOUT_PATH, """{"refreshToken":"${tokens.refreshToken}"}""")

                // when
                val response = post(LOGOUT_PATH, """{"refreshToken":"${tokens.refreshToken}"}""")

                // then
                assertSoftly {
                    response.statusCode() shouldBe 401
                    body(response) shouldBe mapOf("code" to "COMMON_UNAUTHORIZED", "message" to "인증이 필요합니다.", "errors" to null)
                }
            }

            test("Access Token도 본문도 없으면 401 COMMON_UNAUTHORIZED를 반환한다") {
                // given
                val path = LOGOUT_PATH

                // when
                val response = post(path, requestBody = null)

                // then
                response.statusCode() shouldBe 401
                body(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
            }

            val invalidBodies =
                mapOf(
                    "refreshToken 누락" to "{}",
                    "refreshToken이 빈 문자열" to """{"refreshToken":""}""",
                    "정의하지 않은 필드" to """{"refreshToken":"token","deviceId":"d"}""",
                )
            for ((case, requestBody) in invalidBodies) {
                test("Access Token 없이 보낸 본문의 형식 오류($case)는 400 COMMON_INVALID_REQUEST를 반환한다") {
                    // given
                    val path = LOGOUT_PATH

                    // when
                    val response = post(path, requestBody)

                    // then
                    response.statusCode() shouldBe 400
                    body(response)["code"] shouldBe "COMMON_INVALID_REQUEST"
                }
            }
        }
    }

    private fun newProviderUserId(): String = "test-${UUID.randomUUID()}".also { providerUserIds.add(it) }

    private fun createActiveMember(): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO member (social_provider, social_provider_user_id, status, created_at, updated_at) " +
                    "VALUES ('GOOGLE', ?, 'ACTIVE', now(), now()) RETURNING id",
                Long::class.java,
                newProviderUserId(),
            ),
        )

    private fun countMembers(providerUserId: String): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM member WHERE social_provider_user_id = ?", Int::class.java, providerUserId))

    private fun post(
        path: String,
        requestBody: String?,
        accessToken: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}$path"))
        if (requestBody == null) {
            request.POST(HttpRequest.BodyPublishers.noBody())
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(requestBody))
        }
        if (accessToken != null) {
            request.header("Authorization", "Bearer $accessToken")
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun body(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)

    companion object {
        private const val SOCIAL_LOGIN_PATH = "/api/v1/auth/social-logins"
        private const val TOKEN_REFRESH_PATH = "/api/v1/auth/token-refreshes"
        private const val LOGOUT_PATH = "/api/v1/auth/logout"
        private const val GOOGLE_CLIENT_ID = "test-google-client-id"
        private const val KAKAO_APP_KEY = "test-kakao-native-app-key"
        private const val KAKAO_ISSUER = SocialIdTokenFixture.KAKAO_ISSUER

        private val fixture = SocialIdTokenFixture()

        @DynamicPropertySource
        @JvmStatic
        fun socialLoginProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.auth.social.google.jwk-set-uri") { fixture.googleJwkSetUri }
            // 카카오는 JWKS에 연결할 수 없는 제공자 장애 상황을 검증하는 데 사용합니다.
            val closedPort = ServerSocket(0).use { it.localPort }
            registry.add("app.auth.social.kakao.jwk-set-uri") { "http://127.0.0.1:$closedPort/jwks" }
        }
    }
}
