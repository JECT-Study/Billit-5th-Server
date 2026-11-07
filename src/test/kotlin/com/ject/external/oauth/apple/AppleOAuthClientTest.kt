package com.ject.external.oauth.apple

import com.ject.external.oauth.OAuthClientException
import com.ject.external.oauth.OidcIdTokenVerifier
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.Date

class AppleOAuthClientTest :
    FunSpec({
        lateinit var server: MockRestServiceServer
        lateinit var client: AppleOAuthClient
        val key = RSAKeyGenerator(2048).keyID("provider-key").generate()
        val verifier =
            OidcIdTokenVerifier(
                "client",
                setOf("https://issuer.example"),
                ImmutableJWKSet<SecurityContext>(JWKSet(key.toPublicJWK())),
            )

        fun signedToken(signingKey: com.nimbusds.jose.jwk.RSAKey = key): String {
            val claims =
                JWTClaimsSet
                    .Builder()
                    .issuer("https://issuer.example")
                    .audience("client")
                    .subject("provider-subject")
                    .claim("nonce", "expected")
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build()
            val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("provider-key").build(), claims)
            jwt.sign(RSASSASigner(signingKey))
            return jwt.serialize()
        }
        var idToken = signedToken()

        beforeTest {
            idToken = signedToken()
            val builder = RestClient.builder().baseUrl("https://provider.example")
            server = MockRestServiceServer.bindTo(builder).build()
            client = AppleOAuthClient(builder.build(), AppleOAuthProperties("client", "secret", "https://app.example/callback"), verifier)
        }

        afterTest { server.verify() }

        test("인가 코드를 교환하고 제공자 사용자 식별자를 반환한다") {
            // given
            server
                .expect(requestTo("https://provider.example/auth/token"))
                .andExpect { request ->
                    val body = (request as MockClientHttpRequest).bodyAsString
                    body shouldContain "grant_type=authorization_code"
                    body shouldContain "client_id=client"
                    body shouldContain "code=code"
                    body shouldContain "client_secret=secret"
                }.andRespond(withSuccess("""{"id_token":"$idToken","extra":"ignored"}""", MediaType.APPLICATION_JSON))

            // when
            val identity = client.fetchIdentity("code")

            // then
            identity.providerUserId shouldBe "provider-subject"
        }

        test("만료되거나 재사용한 코드는 인증 거절로 구분하고 응답 원문을 노출하지 않는다") {
            // given
            server
                .expect(requestTo("https://provider.example/auth/token"))
                .andRespond(
                    withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""{"error":"invalid_grant","error_description":"sensitive"}"""),
                )

            // when
            val failure = shouldThrow<OAuthClientException> { client.fetchIdentity("code") }

            // then
            failure.reason shouldBe OAuthClientException.Reason.INVALID_AUTHORIZATION
            failure.statusCode shouldBe 400
            failure.cause shouldBe null
            failure.message?.contains("sensitive") shouldBe false
        }

        test("제공자 장애는 잘못된 인가 코드와 구분한다") {
            // given
            server
                .expect(requestTo("https://provider.example/auth/token"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))

            // when
            val failure = shouldThrow<OAuthClientException> { client.fetchIdentity("code") }

            // then
            failure.reason shouldBe OAuthClientException.Reason.UNAVAILABLE
        }

        test("검증할 수 없는 ID token은 신원으로 반환하지 않는다") {
            // given
            idToken = signedToken(RSAKeyGenerator(2048).generate())
            server
                .expect(requestTo("https://provider.example/auth/token"))
                .andRespond(withSuccess("""{"id_token":"$idToken","extra":"ignored"}""", MediaType.APPLICATION_JSON))

            // when
            val failure = shouldThrow<OAuthClientException> { client.fetchIdentity("code") }

            // then
            failure.reason shouldBe OAuthClientException.Reason.INVALID_RESPONSE
        }

        test("로그인 요청의 nonce와 ID token의 nonce가 다르면 거절한다") {
            // given
            server
                .expect(requestTo("https://provider.example/auth/token"))
                .andRespond(withSuccess("""{"id_token":"$idToken","extra":"ignored"}""", MediaType.APPLICATION_JSON))

            // when
            val failure = shouldThrow<OAuthClientException> { client.fetchIdentity("code", expectedNonce = "other") }

            // then
            failure.reason shouldBe OAuthClientException.Reason.INVALID_AUTHORIZATION
        }
    })
