package com.ject.module.auth.infra

import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.module.auth.implement.LoginHistoryCreator
import com.ject.module.auth.implement.RefreshTokenHasher
import com.ject.support.IntegrationTestSupport
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["app.api-docs.enabled=true"])
@Import(SecurityConfigTest.AuthenticationProbeController::class)
class SecurityConfigTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    @Autowired
    private lateinit var jwtEncoder: JwtEncoder

    @Autowired
    private lateinit var loginHistoryCreator: LoginHistoryCreator

    @MockitoSpyBean
    private lateinit var authSessionRepository: AuthSessionRepository

    @Autowired
    private lateinit var jsonMapper: JsonMapper

    private val httpClient = HttpClient.newHttpClient()
    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("배포 상태 확인 경로 /healthz와 /readyz는 토큰 없이 200을 반환한다") {
            // given
            val paths = listOf("/healthz", "/readyz")

            // when
            val statuses = paths.map { get(serverUrl(it)).statusCode() }

            // then
            statuses shouldBe listOf(200, 200)
        }

        test("배포 상태 확인 경로 /healthz와 /readyz는 만료된 토큰을 함께 보내도 200을 반환한다") {
            // given
            val memberId = createMember()
            val expiredAccessToken = expiredCreator(Duration.ofMinutes(16)).createMemberTokens(memberId).accessToken
            val paths = listOf("/healthz", "/readyz")

            // when
            val statuses = paths.map { get(serverUrl(it), expiredAccessToken).statusCode() }

            // then
            statuses shouldBe listOf(200, 200)
        }

        test("관리 포트의 상태 확인과 지표 경로는 토큰 없이 200을 반환한다") {
            // given
            val paths = listOf("/actuator/health", "/actuator/prometheus")

            // when
            val statuses = paths.map { get(managementUrl(it)).statusCode() }

            // then
            statuses shouldBe listOf(200, 200)
        }

        test("API 문서 경로는 토큰 없이 200을 반환한다") {
            // given
            val paths = listOf("/swagger-ui.html", "/api-docs/openapi.yml", "/scalar/scalar-initializer.js")

            // when
            val statuses = paths.map { get(serverUrl(it)).statusCode() }

            // then
            statuses shouldBe listOf(200, 200, 200)
        }

        test("소셜 로그인과 토큰 갱신 경로는 만료되거나 잘못된 토큰을 함께 보내도 인증 오류로 거절하지 않는다") {
            // given
            val paths = listOf("/api/v1/auth/social-logins", "/api/v1/auth/token-refreshes")

            // when
            val responses = paths.map { post(serverUrl(it), token = "invalid-token") }

            // then
            responses.map { errorBody(it)["code"] } shouldNotContain "COMMON_UNAUTHORIZED"
        }

        test("로그아웃 경로는 토큰 없이 호출해도 인증 오류로 거절하지 않는다") {
            // given
            val url = serverUrl("/api/v1/auth/logout")

            // when
            val response = post(url, token = null)

            // then
            errorBody(response)["code"] shouldNotBe "COMMON_UNAUTHORIZED"
        }

        test("로그아웃 경로에 잘못된 토큰을 보내면 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val url = serverUrl("/api/v1/auth/logout")

            // when
            val response = post(url, token = "invalid-token")

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("토큰 없이 회원 API를 호출하면 401 COMMON_UNAUTHORIZED를 공통 오류 형식으로 반환한다") {
            // given
            val url = serverUrl(MEMBER_PROBE_PATH)

            // when
            val response = get(url)

            // then
            assertSoftly {
                response.statusCode() shouldBe 401
                response.headers().firstValue("Content-Type").orElse("") shouldBe "application/json;charset=UTF-8"
                errorBody(response) shouldBe mapOf("code" to "COMMON_UNAUTHORIZED", "message" to "인증이 필요합니다.", "errors" to null)
            }
        }

        test("유효한 회원용 Access Token으로 회원 API를 호출하면 해당 회원으로 인증된다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), tokens.accessToken)

            // then
            response.statusCode() shouldBe 200
            response.body() shouldBe memberId.toString()
        }

        test("만료된 회원용 Access Token은 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = expiredCreator(Duration.ofMinutes(16)).createMemberTokens(memberId)

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), tokens.accessToken)

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("다른 키로 서명한 토큰은 회원 정보와 세션이 맞아도 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)
            val claims = SignedJWT.parse(tokens.accessToken).jwtClaimsSet
            val forged = SignedJWT(JWSHeader(JWSAlgorithm.HS512), claims)
            forged.sign(MACSigner(ByteArray(64) { 7 }))

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), forged.serialize())

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("같은 키로 서명해도 HS512가 아닌 HS256 토큰은 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)
            val claims = SignedJWT.parse(tokens.accessToken).jwtClaimsSet

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), sign(claims, JWSAlgorithm.HS256))

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("서명이 없는 alg none 토큰은 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)
            val claims = SignedJWT.parse(tokens.accessToken).jwtClaimsSet
            val unsigned = PlainJWT(claims).serialize()

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), unsigned)

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("Refresh Token을 Authorization 헤더로 보내면 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), tokens.refreshToken)

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("종료된 세션의 회원용 Access Token은 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)
            jdbc.update("UPDATE auth_session SET revoked_at = now() WHERE member_id = ?", memberId)

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), tokens.accessToken)

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("세션이 없는 회원용 Access Token은 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val now = Instant.now()
            val claims =
                JWTClaimsSet
                    .Builder()
                    .subject(memberId.toString())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(15))))
                    .claim("token_type", "ACCESS")
                    .claim("sid", UUID.randomUUID().toString())
                    .build()

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), sign(claims))

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("다른 회원의 세션 ID를 담은 회원용 Access Token은 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val sessionOwnerId = createMember()
            val otherMemberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(sessionOwnerId)
            val claims =
                JWTClaimsSet
                    .Builder(SignedJWT.parse(tokens.accessToken).jwtClaimsSet)
                    .subject(otherMemberId.toString())
                    .build()

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), sign(claims))

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("토큰 종류 클레임이 없는 토큰은 세션이 유효해도 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)
            val claims =
                JWTClaimsSet
                    .Builder(SignedJWT.parse(tokens.accessToken).jwtClaimsSet)
                    .claim("token_type", null)
                    .build()

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), sign(claims))

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("만료 시각이 없는 회원용 Access Token은 세션이 유효해도 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)
            val claims =
                JWTClaimsSet
                    .Builder(SignedJWT.parse(tokens.accessToken).jwtClaimsSet)
                    .expirationTime(null)
                    .build()

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), sign(claims))

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        for (failure in listOf(
            CannotCreateTransactionException("DB 연결을 얻지 못함"),
            DataAccessResourceFailureException("DB 응답 실패"),
        )) {
            test("세션 조회 중 DB 오류(${failure.javaClass.simpleName})가 나면 401 대신 503 COMMON_SERVICE_UNAVAILABLE을 반환한다") {
                // given
                val memberId = createMember()
                val tokens = authTokenCreator.createMemberTokens(memberId)
                val sessionId = UUID.fromString(SignedJWT.parse(tokens.accessToken).jwtClaimsSet.getStringClaim("sid"))
                doThrow(failure).`when`(authSessionRepository).findById(sessionId)

                // when
                val response = get(serverUrl(MEMBER_PROBE_PATH), tokens.accessToken)

                // then
                assertSoftly {
                    response.statusCode() shouldBe 503
                    response.headers().firstValue("WWW-Authenticate").isPresent shouldBe false
                    errorBody(response) shouldBe
                        mapOf("code" to "COMMON_SERVICE_UNAVAILABLE", "message" to "일시적으로 요청을 처리할 수 없습니다.", "errors" to null)
                }
            }
        }

        test("만료된 회원용 Access Token은 세션을 조회하지 않고 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = expiredCreator(Duration.ofMinutes(16)).createMemberTokens(memberId)
            val sessionId = UUID.fromString(SignedJWT.parse(tokens.accessToken).jwtClaimsSet.getStringClaim("sid"))
            doThrow(DataAccessResourceFailureException("세션을 조회하면 안 됨")).`when`(authSessionRepository).findById(sessionId)

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), tokens.accessToken)

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("가입 전용 토큰으로 회원 API를 호출하면 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val registrationToken = authTokenCreator.createRegistrationToken(memberId)

            // when
            val response = get(serverUrl(MEMBER_PROBE_PATH), registrationToken)

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("토큰 없이 가입 API를 호출하면 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val url = serverUrl(REGISTRATION_PROBE_PATH)

            // when
            val response = get(url)

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("회원용 Access Token으로 가입 API를 호출하면 403 COMMON_FORBIDDEN을 반환한다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)

            // when
            val responses =
                listOf(REGISTRATION_PROBE_PATH).map {
                    get(serverUrl(it), tokens.accessToken)
                }

            // then
            assertSoftly {
                responses.forEach {
                    it.statusCode() shouldBe 403
                    errorBody(it) shouldBe
                        mapOf("code" to "COMMON_FORBIDDEN", "message" to "요청을 수행할 권한이 없습니다.", "errors" to null)
                }
            }
        }

        test("가입 전용 토큰으로 가입 API를 호출하면 해당 회원으로 인증된다") {
            // given
            val memberId = createMember()
            val registrationToken = authTokenCreator.createRegistrationToken(memberId)

            // when
            val response = get(serverUrl(REGISTRATION_PROBE_PATH), registrationToken)

            // then
            response.statusCode() shouldBe 200
            response.body() shouldBe memberId.toString()
        }

        test("만료된 가입 전용 토큰으로 가입 API를 호출하면 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val registrationToken = expiredCreator(Duration.ofMinutes(31)).createRegistrationToken(memberId)

            // when
            val response = get(serverUrl(REGISTRATION_PROBE_PATH), registrationToken)

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }

        test("Refresh Token으로 가입 API를 호출하면 401 COMMON_UNAUTHORIZED를 반환한다") {
            // given
            val memberId = createMember()
            val tokens = authTokenCreator.createMemberTokens(memberId)

            // when
            val response = get(serverUrl(REGISTRATION_PROBE_PATH), tokens.refreshToken)

            // then
            response.statusCode() shouldBe 401
            errorBody(response)["code"] shouldBe "COMMON_UNAUTHORIZED"
        }
    }

    private fun serverUrl(path: String): URI = URI.create("http://localhost:${environment.getRequiredProperty("local.server.port")}$path")

    private fun managementUrl(path: String): URI =
        URI.create("http://localhost:${environment.getRequiredProperty("local.management.port")}$path")

    private fun get(
        uri: URI,
        token: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri).GET()
        if (token != null) {
            request.header("Authorization", "Bearer $token")
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        uri: URI,
        token: String?,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
        if (token != null) {
            request.header("Authorization", "Bearer $token")
        }
        return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** 테스트 설정의 서명 키로 서명합니다. */
    private fun sign(
        claims: JWTClaimsSet,
        algorithm: JWSAlgorithm = JWSAlgorithm.HS512,
    ): String {
        val token = SignedJWT(JWSHeader(algorithm), claims)
        token.sign(MACSigner(Base64.getDecoder().decode(environment.getRequiredProperty("app.auth.jwt.secret"))))
        return token.serialize()
    }

    /** 지정한 시간만큼 이전에 발급한 토큰을 만드는 발급기입니다. */
    private fun expiredCreator(issuedBefore: Duration): AuthTokenCreator =
        AuthTokenCreator(
            jwtEncoder,
            authSessionRepository,
            RefreshTokenHasher(),
            loginHistoryCreator,
            Clock.fixed(Instant.now().minus(issuedBefore), ZoneOffset.UTC),
        )

    private fun errorBody(response: HttpResponse<String>): Map<*, *> = jsonMapper.readValue(response.body(), Map::class.java)

    private fun createMember(): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) " +
                        "VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(id)
        return id
    }

    companion object {
        private const val MEMBER_PROBE_PATH = "/api/v1/test/authentication-probe"
        private const val REGISTRATION_PROBE_PATH = "/api/v1/me/registration/authentication-probe"
    }

    /** 인증 결과를 관찰하기 위한 테스트 전용 엔드포인트입니다. 인증된 토큰의 회원 ID를 반환하며 이 테스트의 컨텍스트에만 등록합니다. */
    @TestComponent
    @RestController
    class AuthenticationProbeController {
        @GetMapping(MEMBER_PROBE_PATH, REGISTRATION_PROBE_PATH)
        fun probe(
            @AuthenticationPrincipal jwt: Jwt,
        ): String = checkNotNull(jwt.subject)
    }
}
