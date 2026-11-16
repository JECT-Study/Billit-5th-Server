package com.ject.module.auth.infra.client

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.infra.SocialLoginProperties
import com.ject.module.member.domain.AuthProvider
import com.ject.support.SocialIdTokenFixture
import com.ject.support.SocialIdTokenFixture.Companion.GOOGLE_ISSUER
import com.ject.support.SocialIdTokenFixture.Companion.KAKAO_ISSUER
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.ServerSocket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SocialIdTokenAdapterTest :
    FunSpec({
        lateinit var fixture: SocialIdTokenFixture
        lateinit var adapter: SocialIdTokenAdapter
        val clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC)
        val now = clock.instant()

        fun adapter(
            googleJwkSetUri: String,
            kakaoJwkSetUri: String,
        ) = SocialIdTokenAdapter(
            SocialLoginProperties(
                google = SocialLoginProperties.Google(clientId = GOOGLE_CLIENT_ID, jwkSetUri = googleJwkSetUri),
                kakao = SocialLoginProperties.Kakao(nativeAppKey = KAKAO_APP_KEY, jwkSetUri = kakaoJwkSetUri),
            ),
            clock,
        )

        beforeTest {
            fixture = SocialIdTokenFixture(clock)
            adapter = adapter(fixture.googleJwkSetUri, fixture.kakaoJwkSetUri)
        }

        afterTest { fixture.close() }

        test("구글 ID 토큰의 서명, iss, aud, exp가 유효하면 외부 사용자 ID를 반환한다") {
            // given
            val idToken = fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "google-user-1", now.plusSeconds(600))

            // when
            val subject = adapter.verify(AuthProvider.GOOGLE, idToken)

            // then
            subject shouldBe "google-user-1"
        }

        test("구글 ID 토큰의 iss가 스킴 없는 accounts.google.com이어도 허용한다") {
            // given
            val idToken = fixture.idToken("accounts.google.com", GOOGLE_CLIENT_ID, "google-user-1", now.plusSeconds(600))

            // when
            val subject = adapter.verify(AuthProvider.GOOGLE, idToken)

            // then
            subject shouldBe "google-user-1"
        }

        test("카카오 ID 토큰의 서명, iss, aud, exp가 유효하면 외부 사용자 ID를 반환한다") {
            // given
            val idToken = fixture.idToken(KAKAO_ISSUER, KAKAO_APP_KEY, "1234567890", now.plusSeconds(600))

            // when
            val subject = adapter.verify(AuthProvider.KAKAO, idToken)

            // then
            subject shouldBe "1234567890"
        }

        context("ID 토큰이 유효하지 않으면 401 AUTH_SOCIAL_AUTHENTICATION_FAILED로 실패한다") {
            val otherKey = RSAKeyGenerator(2048).keyID("test-key").generate()
            val invalidTokens =
                mapOf(
                    "같은 kid의 다른 키로 서명" to { fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "u", now.plusSeconds(600), otherKey) },
                    "aud가 다른 클라이언트 ID" to { fixture.idToken(GOOGLE_ISSUER, "other-client-id", "u", now.plusSeconds(600)) },
                    "iss가 다른 제공자" to { fixture.idToken(KAKAO_ISSUER, GOOGLE_CLIENT_ID, "u", now.plusSeconds(600)) },
                    "허용 오차 60초를 넘겨 만료" to { fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "u", now.minusSeconds(120)) },
                    "exp 없음" to { fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "u", null) },
                    "sub 없음" to { fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, null, now.plusSeconds(600)) },
                    "JWT 형식이 아님" to { "not-a-jwt" },
                )
            for ((case, idToken) in invalidTokens) {
                test(case) {
                    // given
                    val token = idToken()

                    // when
                    val failure = shouldThrow<BusinessException> { adapter.verify(AuthProvider.GOOGLE, token) }

                    // then
                    failure.errorCode shouldBe AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED
                }
            }
        }

        test("구글 ID 토큰을 카카오 로그인으로 보내면 401 AUTH_SOCIAL_AUTHENTICATION_FAILED로 실패한다") {
            // given
            val idToken = fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "google-user-1", now.plusSeconds(600))

            // when
            val failure = shouldThrow<BusinessException> { adapter.verify(AuthProvider.KAKAO, idToken) }

            // then
            failure.errorCode shouldBe AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED
        }

        test("제공자 JWKS가 500으로 응답하면 503 AUTH_SOCIAL_PROVIDER_UNAVAILABLE로 실패한다") {
            // given
            fixture.jwksUnavailable.set(true)
            val idToken = fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "google-user-1", now.plusSeconds(600))

            // when
            val failure = shouldThrow<BusinessException> { adapter.verify(AuthProvider.GOOGLE, idToken) }

            // then
            failure.errorCode shouldBe AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE
        }

        test("제공자 JWKS에 연결할 수 없으면 503 AUTH_SOCIAL_PROVIDER_UNAVAILABLE로 실패한다") {
            // given
            val closedPort = ServerSocket(0).use { it.localPort }
            val unreachableAdapter = adapter("http://127.0.0.1:$closedPort/jwks", fixture.kakaoJwkSetUri)
            val idToken = fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "google-user-1", now.plusSeconds(600))

            // when
            val failure = shouldThrow<BusinessException> { unreachableAdapter.verify(AuthProvider.GOOGLE, idToken) }

            // then
            failure.errorCode shouldBe AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE
        }

        test("같은 제공자의 ID 토큰을 여러 번 검증해도 JWKS는 한 번만 가져온다") {
            // given
            val idTokens = (1..3).map { fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "user-$it", now.plusSeconds(600)) }

            // when
            val subjects = idTokens.map { adapter.verify(AuthProvider.GOOGLE, it) }

            // then
            subjects shouldBe listOf("user-1", "user-2", "user-3")
            fixture.jwksRequestCount.get() shouldBe 1
        }

        test("JWKS에 없는 키의 토큰이 연달아 와도 30초 동안 JWKS를 두 번까지만 가져오고 401로 실패한다") {
            // given
            val unknownKey = RSAKeyGenerator(2048).keyID("unknown-key").generate()
            val idTokens = (1..5).map { fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "u", now.plusSeconds(600), unknownKey) }

            // when
            val errorCodes = idTokens.map { shouldThrow<BusinessException> { adapter.verify(AuthProvider.GOOGLE, it) }.errorCode }

            // then
            errorCodes.toSet() shouldBe setOf(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED)
            fixture.jwksRequestCount.get() shouldBe 2
        }
    }) {
    companion object {
        private const val GOOGLE_CLIENT_ID = "test-google-client-id"
        private const val KAKAO_APP_KEY = "test-kakao-native-app-key"
    }
}
