package com.ject.module.auth.infra

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.BadJWTException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

class JwtAccessTokenCodecTest :
    FunSpec({
        val now = Instant.parse("2026-10-23T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val properties = JwtProperties("test-only-jwt-secret-at-least-32-bytes", "billit", Duration.ofHours(1))
        val codec = JwtAccessTokenCodec(properties, clock)

        test("발급한 access token은 사용자 ID와 설정한 발급자 및 만료 시각을 담는다") {
            // given
            val userId = "user-123"
            // when
            val token = codec.issue(userId)
            val claims = SignedJWT.parse(token).jwtClaimsSet
            // then
            codec.verify(token) shouldBe userId
            claims.issuer shouldBe "billit"
            claims.issueTime.toInstant() shouldBe now
            claims.expirationTime.toInstant() shouldBe now.plusSeconds(3600)
        }

        test("만료 직전에는 허용하고 만료 시각부터 거부한다") {
            // given
            val token = codec.issue("user-123")
            val beforeExpiry = JwtAccessTokenCodec(properties, Clock.fixed(now.plusSeconds(3599), ZoneOffset.UTC))
            val atExpiry = JwtAccessTokenCodec(properties, Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC))
            val afterExpiry = JwtAccessTokenCodec(properties, Clock.fixed(now.plusSeconds(3601), ZoneOffset.UTC))
            // when
            val userId = beforeExpiry.verify(token)
            // then
            userId shouldBe "user-123"
            shouldThrow<BadJWTException> { atExpiry.verify(token) }
            shouldThrow<BadJWTException> { afterExpiry.verify(token) }
        }

        test("다른 비밀키로 서명한 토큰은 거부한다") {
            // given
            val other = JwtAccessTokenCodec(JwtProperties("other-test-secret-at-least-32-bytes", "billit", Duration.ofHours(1)), clock)
            val token = other.issue("user-123")
            // when
            val failure = shouldThrow<BadJWTException> { codec.verify(token) }
            // then
            failure.message shouldBe "Invalid access token signature"
        }

        val invalidClaims: List<Pair<String, (JWTClaimsSet.Builder) -> Unit>> =
            listOf(
                "다른 발급자" to { it.issuer("other") },
                "만료 시각 누락" to { it.expirationTime(null) },
                "발급 시각 누락" to { it.issueTime(null) },
                "사용자 ID 누락" to { it.subject(null) },
                "빈 사용자 ID" to { it.subject(" ") },
                "유효 시작 시각 이전" to { it.notBeforeTime(Date.from(now.plusSeconds(60))) },
            )
        invalidClaims.forEach { (condition, change) ->
            test("$condition 토큰은 거부한다") {
                // given
                val builder =
                    JWTClaimsSet
                        .Builder()
                        .issuer("billit")
                        .subject("user-123")
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(3600)))
                change(builder)
                val token = SignedJWT(JWSHeader(JWSAlgorithm.HS256), builder.build())
                token.sign(MACSigner(properties.secret))
                // when
                val failure = shouldThrow<BadJWTException> { codec.verify(token.serialize()) }
                // then
                failure.message.isNullOrBlank() shouldBe false
            }
        }

        test("HS256 이외의 알고리즘으로 서명한 토큰은 거부한다") {
            // given
            val claims = SignedJWT.parse(codec.issue("user-123")).jwtClaimsSet
            val secret = "test-secret-for-hs512-algorithm-with-at-least-sixty-four-bytes-long-value"
            val token = SignedJWT(JWSHeader(JWSAlgorithm.HS512), claims)
            token.sign(MACSigner(secret))
            val verifier = JwtAccessTokenCodec(JwtProperties(secret, "billit", Duration.ofHours(1)), clock)
            // when
            val failure = shouldThrow<BadJWTException> { verifier.verify(token.serialize()) }
            // then
            failure.message shouldBe "Invalid access token signature"
        }
    })
