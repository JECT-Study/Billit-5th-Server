package com.ject.module.auth.presentation

import com.ject.support.IntegrationTestSupport
import com.ject.support.SocialIdTokenFixture
import com.ject.support.SocialIdTokenFixture.Companion.GOOGLE_ISSUER
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
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
                jdbc.update(
                    "INSERT INTO member (social_provider, social_provider_user_id, status, created_at, updated_at) " +
                        "VALUES ('GOOGLE', ?, 'ACTIVE', now(), now())",
                    providerUserId,
                )
                val idToken = fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, providerUserId, Instant.now().plusSeconds(600))

                // when
                val response = post(SOCIAL_LOGIN_PATH, """{"provider":"GOOGLE","idToken":"$idToken"}""")

                // then
                val data = body(response)["data"] as Map<*, *>
                assertSoftly {
                    response.statusCode() shouldBe 200
                    data.keys shouldBe setOf("status", "accessToken", "refreshToken")
                    data["status"] shouldBe "AUTHENTICATED"
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
    }

    private fun newProviderUserId(): String = "test-${UUID.randomUUID()}".also { providerUserIds.add(it) }

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
