package com.ject.module.auth.infra.client

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.domain.SocialIdentity
import com.ject.module.auth.infra.SocialLoginProperties
import com.ject.module.member.domain.AuthProvider
import com.ject.support.SocialIdTokenFixture
import com.ject.support.SocialIdTokenFixture.Companion.APPLE_ISSUER
import com.ject.support.SocialIdTokenFixture.Companion.GOOGLE_ISSUER
import com.ject.support.SocialIdTokenFixture.Companion.KAKAO_ISSUER
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.ServerSocket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

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
                apple =
                    SocialLoginProperties.Apple(
                        clientId = APPLE_CLIENT_ID,
                        teamId = "test-apple-team-id",
                        keyId = "test-apple-key-id",
                        privateKeyBase64 = "",
                        jwkSetUri = fixture.appleJwkSetUri,
                        tokenUri = "http://127.0.0.1/apple/token",
                        revokeUri = "http://127.0.0.1/apple/revoke",
                    ),
            ),
            clock,
        )

        beforeEach {
            fixture = SocialIdTokenFixture(clock)
            adapter = adapter(fixture.googleJwkSetUri, fixture.kakaoJwkSetUri)
        }

        afterEach { fixture.close() }

        test("구글 ID 토큰의 서명, iss, aud, exp가 유효하면 외부 사용자 ID를 반환한다") {
            // given
            val idToken = fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "google-user-1", now.plusSeconds(600))

            // when
            val identity = adapter.verify(AuthProvider.GOOGLE, idToken)

            // then
            identity shouldBe SocialIdentity(subject = "google-user-1", displayName = null)
        }

        test("구글 ID 토큰의 name과 카카오 ID 토큰의 nickname을 표시 이름으로 반환한다") {
            // given
            val googleIdToken =
                fixture.idToken(
                    GOOGLE_ISSUER,
                    GOOGLE_CLIENT_ID,
                    "google-user-1",
                    now.plusSeconds(600),
                    extraClaims = mapOf("name" to "홍길동"),
                )
            val kakaoIdToken =
                fixture.idToken(KAKAO_ISSUER, KAKAO_APP_KEY, "1234567890", now.plusSeconds(600), extraClaims = mapOf("nickname" to "길동"))

            // when
            val identities = listOf(adapter.verify(AuthProvider.GOOGLE, googleIdToken), adapter.verify(AuthProvider.KAKAO, kakaoIdToken))

            // then
            identities.map { it.displayName } shouldBe listOf("홍길동", "길동")
        }

        test("표시 이름 클레임이 공백이면 표시 이름 없이 반환한다") {
            // given
            val idToken =
                fixture.idToken(
                    GOOGLE_ISSUER,
                    GOOGLE_CLIENT_ID,
                    "google-user-1",
                    now.plusSeconds(600),
                    extraClaims =
                        mapOf("name" to "  "),
                )

            // when
            val identity = adapter.verify(AuthProvider.GOOGLE, idToken)

            // then
            identity.displayName shouldBe null
        }

        test("aud 배열에 클라이언트 ID가 포함되어 있으면 허용한다") {
            // given
            val idToken =
                fixture.idTokenWithAudiences(
                    GOOGLE_ISSUER,
                    listOf("other-client-id", GOOGLE_CLIENT_ID),
                    "google-user-1",
                    now.plusSeconds(600),
                )

            // when
            val identity = adapter.verify(AuthProvider.GOOGLE, idToken)

            // then
            identity.subject shouldBe "google-user-1"
        }

        test("구글 ID 토큰의 iss가 스킴 없는 accounts.google.com이어도 허용한다") {
            // given
            val idToken = fixture.idToken("accounts.google.com", GOOGLE_CLIENT_ID, "google-user-1", now.plusSeconds(600))

            // when
            val identity = adapter.verify(AuthProvider.GOOGLE, idToken)

            // then
            identity.subject shouldBe "google-user-1"
        }

        test("카카오 ID 토큰의 서명, iss, aud, exp가 유효하면 외부 사용자 ID를 반환한다") {
            // given
            val idToken = fixture.idToken(KAKAO_ISSUER, KAKAO_APP_KEY, "1234567890", now.plusSeconds(600))

            // when
            val identity = adapter.verify(AuthProvider.KAKAO, idToken)

            // then
            identity shouldBe SocialIdentity(subject = "1234567890", displayName = null)
        }

        test("애플 ID 토큰의 서명, iss, aud, exp가 유효하면 표시 이름 없이 외부 사용자 ID를 반환한다") {
            // given
            val idToken =
                fixture.idToken(
                    APPLE_ISSUER,
                    APPLE_CLIENT_ID,
                    "001234.apple.user",
                    now.plusSeconds(600),
                    extraClaims =
                        mapOf("name" to "무시할 이름"),
                )

            // when
            val identity = adapter.verify(AuthProvider.APPLE, idToken)

            // then
            identity shouldBe SocialIdentity(subject = "001234.apple.user", displayName = null)
        }

        test("애플 ID 토큰의 aud가 앱 번들 ID가 아니거나 다른 제공자의 토큰이면 401 AUTH_SOCIAL_AUTHENTICATION_FAILED로 실패한다") {
            // given
            val idTokens =
                listOf(
                    fixture.idToken(APPLE_ISSUER, "other.bundle.id", "u", now.plusSeconds(600)),
                    fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "u", now.plusSeconds(600)),
                )

            // when
            val errorCodes = idTokens.map { shouldThrow<BusinessException> { adapter.verify(AuthProvider.APPLE, it) }.errorCode }

            // then
            errorCodes shouldBe List(2) { AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED }
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
                    "HS256으로 서명" to { hs256IdToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "u", now.plusSeconds(600)) },
                    "alg가 none인 서명 없는 토큰" to { unsignedIdToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "u", now.plusSeconds(600)) },
                    "aud 배열에 클라이언트 ID가 없음" to {
                        fixture.idTokenWithAudiences(
                            GOOGLE_ISSUER,
                            listOf("other-client-id", "another-client-id"),
                            "u",
                            now.plusSeconds(600),
                        )
                    },
                    "iss 끝에 /가 붙음" to { fixture.idToken("$GOOGLE_ISSUER/", GOOGLE_CLIENT_ID, "u", now.plusSeconds(600)) },
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

        test("카카오 ID 토큰을 구글 로그인으로 보내면 401 AUTH_SOCIAL_AUTHENTICATION_FAILED로 실패한다") {
            // given
            val idToken = fixture.idToken(KAKAO_ISSUER, KAKAO_APP_KEY, "1234567890", now.plusSeconds(600))

            // when
            val failure = shouldThrow<BusinessException> { adapter.verify(AuthProvider.GOOGLE, idToken) }

            // then
            failure.errorCode shouldBe AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED
        }

        test("카카오 ID 토큰의 iss 끝에 /가 붙으면 401 AUTH_SOCIAL_AUTHENTICATION_FAILED로 실패한다") {
            // given
            val idToken = fixture.idToken("$KAKAO_ISSUER/", KAKAO_APP_KEY, "1234567890", now.plusSeconds(600))

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
            subjects.map { it.subject } shouldBe listOf("user-1", "user-2", "user-3")
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

        test("키를 받은 적 없이 제공자 JWKS 장애가 이어지면 재조회 제한에 걸려도 모든 요청이 503 AUTH_SOCIAL_PROVIDER_UNAVAILABLE로 실패한다") {
            // given
            fixture.jwksUnavailable.set(true)
            val idTokens = (1..5).map { fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "user-$it", now.plusSeconds(600)) }

            // when
            val errorCodes = idTokens.map { shouldThrow<BusinessException> { adapter.verify(AuthProvider.GOOGLE, it) }.errorCode }

            // then
            errorCodes shouldBe List(5) { AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE }
        }

        test("키를 받아 둔 뒤 제공자 JWKS 장애가 나도 받아 둔 키의 유효한 토큰은 통과하고 키를 확인할 수 없는 토큰은 503으로 실패한다") {
            // given
            adapter.verify(AuthProvider.GOOGLE, fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "user-0", now.plusSeconds(600)))
            fixture.jwksUnavailable.set(true)
            val unknownKey = RSAKeyGenerator(2048).keyID("rotated-key").generate()
            val rotatedKeyTokens = (1..4).map { fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "u", now.plusSeconds(600), unknownKey) }
            val validTokens = (1..4).map { fixture.idToken(GOOGLE_ISSUER, GOOGLE_CLIENT_ID, "user-$it", now.plusSeconds(600)) }

            // when
            val rotatedKeyErrorCodes =
                rotatedKeyTokens.map { shouldThrow<BusinessException> { adapter.verify(AuthProvider.GOOGLE, it) }.errorCode }
            val subjects = validTokens.map { adapter.verify(AuthProvider.GOOGLE, it).subject }

            // then
            assertSoftly {
                rotatedKeyErrorCodes shouldBe List(4) { AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE }
                subjects shouldBe listOf("user-1", "user-2", "user-3", "user-4")
            }
        }
    }) {
    companion object {
        private fun hs256IdToken(
            issuer: String,
            audience: String,
            subject: String,
            expiresAt: Instant,
        ): String {
            val token =
                SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).keyID("test-key").build(), claims(issuer, audience, subject, expiresAt))
            token.sign(MACSigner(ByteArray(32) { it.toByte() }))
            return token.serialize()
        }

        private fun unsignedIdToken(
            issuer: String,
            audience: String,
            subject: String,
            expiresAt: Instant,
        ): String = PlainJWT(claims(issuer, audience, subject, expiresAt)).serialize()

        private fun claims(
            issuer: String,
            audience: String,
            subject: String,
            expiresAt: Instant,
        ): JWTClaimsSet =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .expirationTime(Date.from(expiresAt))
                .build()

        private const val GOOGLE_CLIENT_ID = "test-google-client-id"
        private const val KAKAO_APP_KEY = "test-kakao-native-app-key"
        private const val APPLE_CLIENT_ID = "app.billit.mobile.test"
    }
}
