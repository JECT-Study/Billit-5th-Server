package com.ject.module.auth.infra.client

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AppleAuthorizationResult
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.infra.SocialLoginProperties
import com.ject.support.AppleAuthServerFixture
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AppleAuthorizationAdapterTest :
    FunSpec({
        lateinit var server: AppleAuthServerFixture
        lateinit var adapter: AppleAuthorizationAdapter
        val now = Instant.parse("2026-11-13T03:00:00Z")

        fun adapter(
            tokenUri: String,
            revokeUri: String,
        ) = AppleAuthorizationAdapter(
            SocialLoginProperties(
                google = SocialLoginProperties.Google(clientId = "google", jwkSetUri = "http://127.0.0.1/google"),
                kakao = SocialLoginProperties.Kakao(nativeAppKey = "kakao", jwkSetUri = "http://127.0.0.1/kakao"),
                apple =
                    SocialLoginProperties.Apple(
                        clientId = CLIENT_ID,
                        teamId = TEAM_ID,
                        keyId = KEY_ID,
                        privateKeyBase64 = server.privateKeyBase64,
                        jwkSetUri = "http://127.0.0.1/apple",
                        tokenUri = tokenUri,
                        revokeUri = revokeUri,
                    ),
            ),
            Clock.fixed(now, ZoneOffset.UTC),
        )

        beforeTest {
            server = AppleAuthServerFixture()
            adapter = adapter(server.tokenUri, server.revokeUri)
        }

        afterTest { server.close() }

        test("인가 코드를 교환하면 폼으로 코드와 client secret을 보내고 응답 ID 토큰의 sub와 Refresh Token을 반환한다") {
            // given
            server.respond(200, server.tokenResponse(refreshToken = "apple-refresh-token", idToken = idToken(subject = "apple-user-1")))

            // when
            val result = adapter.exchangeAuthorizationCode("authorization-code")

            // then
            val request = server.requests.single()
            assertSoftly {
                result shouldBe AppleAuthorizationResult(subject = "apple-user-1", refreshToken = "apple-refresh-token")
                request.path shouldBe "/auth/token"
                request.contentType?.substringBefore(";") shouldBe "application/x-www-form-urlencoded"
                request.form.keys shouldBe setOf("grant_type", "code", "client_id", "client_secret")
                request.form["grant_type"] shouldBe "authorization_code"
                request.form["code"] shouldBe "authorization-code"
                request.form["client_id"] shouldBe CLIENT_ID
            }
        }

        test("client secret은 키 ID를 헤더에 담고 팀 ID, 클라이언트 ID, 애플 aud, 5분 만료로 ES256 서명한 JWT다") {
            // given
            server.respond(200, server.tokenResponse(refreshToken = "apple-refresh-token", idToken = null))

            // when
            adapter.exchangeAuthorizationCode("authorization-code")

            // then
            val clientSecret = SignedJWT.parse(server.requests.single().form["client_secret"])
            val claims = clientSecret.jwtClaimsSet
            assertSoftly {
                clientSecret.header.algorithm shouldBe JWSAlgorithm.ES256
                clientSecret.header.keyID shouldBe KEY_ID
                clientSecret.verify(ECDSAVerifier(server.clientSecretKey.toPublicJWK())) shouldBe true
                claims.issuer shouldBe TEAM_ID
                claims.subject shouldBe CLIENT_ID
                claims.audience shouldBe listOf("https://appleid.apple.com")
                claims.issueTime.toInstant() shouldBe now
                claims.expirationTime.toInstant() shouldBe now.plus(Duration.ofMinutes(5))
            }
        }

        test("교환 응답에 ID 토큰이 없으면 sub 없이 Refresh Token만 반환한다") {
            // given
            server.respond(200, server.tokenResponse(refreshToken = "apple-refresh-token", idToken = null))

            // when
            val result = adapter.exchangeAuthorizationCode("authorization-code")

            // then
            result shouldBe AppleAuthorizationResult(subject = null, refreshToken = "apple-refresh-token")
        }

        context("애플이 인가 코드를 거절하면 401 AUTH_SOCIAL_AUTHENTICATION_FAILED로 실패한다") {
            val rejections =
                mapOf(
                    "만료되거나 이미 사용한 코드(invalid_grant)" to (400 to """{"error":"invalid_grant"}"""),
                    "클라이언트 인증 실패(invalid_client)" to (401 to """{"error":"invalid_client"}"""),
                    "오류 본문이 JSON이 아님" to (400 to "bad request"),
                )
            for ((case, rejection) in rejections) {
                test(case) {
                    // given
                    server.respond(rejection.first, rejection.second)

                    // when
                    val failure = shouldThrow<BusinessException> { adapter.exchangeAuthorizationCode("authorization-code") }

                    // then
                    failure.errorCode shouldBe AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED
                }
            }
        }

        context("애플 장애나 해석할 수 없는 응답이면 503 AUTH_SOCIAL_PROVIDER_UNAVAILABLE로 실패한다") {
            val failures =
                mapOf(
                    "500 응답" to (500 to """{"error":"server_error"}"""),
                    "503 응답" to (503 to ""),
                    "성공 응답의 본문이 JSON이 아님" to (200 to "not-json"),
                    "성공 응답의 ID 토큰이 JWT가 아님" to (200 to """{"refresh_token":"r","id_token":"not-a-jwt"}"""),
                )
            for ((case, failure) in failures) {
                test(case) {
                    // given
                    server.respond(failure.first, failure.second)

                    // when
                    val exception = shouldThrow<BusinessException> { adapter.exchangeAuthorizationCode("authorization-code") }

                    // then
                    exception.errorCode shouldBe AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE
                }
            }
        }

        test("토큰 API가 응답 제한 시간 3초 안에 응답하지 않으면 503 AUTH_SOCIAL_PROVIDER_UNAVAILABLE로 실패한다") {
            // given
            server.respond(200, server.tokenResponse(refreshToken = "r", idToken = null), delay = Duration.ofSeconds(5))

            // when
            val failure = shouldThrow<BusinessException> { adapter.exchangeAuthorizationCode("authorization-code") }

            // then
            failure.errorCode shouldBe AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE
        }

        test("토큰 API에 연결할 수 없으면 503 AUTH_SOCIAL_PROVIDER_UNAVAILABLE로 실패한다") {
            // given
            val closedPort = ServerSocket(0).use { it.localPort }
            val unreachableAdapter = adapter("http://127.0.0.1:$closedPort/auth/token", server.revokeUri)

            // when
            val failure = shouldThrow<BusinessException> { unreachableAdapter.exchangeAuthorizationCode("authorization-code") }

            // then
            failure.errorCode shouldBe AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE
        }

        test("Refresh Token을 폐기하면 폼으로 토큰, refresh_token 힌트와 client secret을 보낸다") {
            // given
            server.respond(200, "")

            // when
            adapter.revokeRefreshToken("apple-refresh-token")

            // then
            val request = server.requests.single()
            assertSoftly {
                request.path shouldBe "/auth/revoke"
                request.form.keys shouldBe setOf("token", "token_type_hint", "client_id", "client_secret")
                request.form["token"] shouldBe "apple-refresh-token"
                request.form["token_type_hint"] shouldBe "refresh_token"
                request.form["client_id"] shouldBe CLIENT_ID
                SignedJWT.parse(request.form["client_secret"]).verify(ECDSAVerifier(server.clientSecretKey.toPublicJWK())) shouldBe true
            }
        }

        test("폐기 요청을 애플이 거절하거나 장애이면 503 AUTH_SOCIAL_PROVIDER_UNAVAILABLE로 실패한다") {
            // given
            val responses = listOf(400 to """{"error":"invalid_client"}""", 500 to "")

            // when
            val errorCodes =
                responses.map { (status, body) ->
                    server.respond(status, body)
                    shouldThrow<BusinessException> { adapter.revokeRefreshToken("apple-refresh-token") }.errorCode
                }

            // then
            errorCodes shouldBe List(2) { AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE }
        }
    }) {
    companion object {
        private const val CLIENT_ID = "app.billit.mobile.test"
        private const val TEAM_ID = "TEAMID1234"
        private const val KEY_ID = "test-apple-key-id"

        /** 교환 응답의 ID 토큰은 sub만 읽으므로 서명 없이 만듭니다. */
        private fun idToken(subject: String): String = PlainJWT(JWTClaimsSet.Builder().subject(subject).build()).serialize()
    }
}
