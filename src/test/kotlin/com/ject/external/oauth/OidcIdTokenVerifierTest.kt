package com.ject.external.oauth

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
import java.time.Instant
import java.util.Date

class OidcIdTokenVerifierTest :
    FunSpec({
        val key = RSAKeyGenerator(2048).keyID("key").generate()
        val verifier =
            OidcIdTokenVerifier(
                "client",
                setOf("https://issuer.example"),
                ImmutableJWKSet<SecurityContext>(JWKSet(key.toPublicJWK())),
            )

        fun token(change: (JWTClaimsSet.Builder) -> Unit): String {
            val builder =
                JWTClaimsSet
                    .Builder()
                    .issuer("https://issuer.example")
                    .audience("client")
                    .subject("user")
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            change(builder)
            val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("key").build(), builder.build())
            jwt.sign(RSASSASigner(key))
            return jwt.serialize()
        }

        test("신뢰하는 키와 발급자 및 대상 앱의 토큰은 사용자 식별자를 반환한다") {
            // given
            val signedToken = token { }

            // when
            val claims = verifier.verify(signedToken)

            // then
            claims.subject shouldBe "user"
        }

        val invalidClaims: List<Pair<String, (JWTClaimsSet.Builder) -> Unit>> =
            listOf(
                "다른 발급자" to { it.issuer("https://other.example") },
                "다른 대상 앱" to { it.audience("other") },
                "만료된 토큰" to { it.expirationTime(Date.from(Instant.now().minusSeconds(120))) },
                "만료 시각 누락" to { it.expirationTime(null) },
                "발급 시각 누락" to { it.issueTime(null) },
                "빈 사용자 식별자" to { it.subject("") },
                "다른 authorized party" to { it.claim("azp", "other") },
                "잘못된 nonce 타입" to { it.claim("nonce", listOf("nonce")) },
            )
        invalidClaims.forEach { (condition, change) ->
            test("$condition 토큰은 신원 확인에 사용할 수 없다") {
                // given
                val signedToken = token(change)

                // when
                val failure = shouldThrow<OAuthClientException> { verifier.verify(signedToken) }

                // then
                failure.reason shouldBe OAuthClientException.Reason.INVALID_RESPONSE
            }
        }
    })
