package com.ject.support

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 구글, 카카오와 애플 대신 자체 서명 키로 ID 토큰을 만들고 JWKS를 제공합니다. 실제 제공자를 호출하지 않습니다.
 *
 * 발급 시각(iat)은 [clock] 기준 10초 전입니다.
 *
 * JWKS 주소는 [googleJwkSetUri], [kakaoJwkSetUri], [appleJwkSetUri]이며 사용 후 [close]로 서버를 닫습니다.
 */
class SocialIdTokenFixture(
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()

    /** JWKS 요청 수입니다. */
    val jwksRequestCount = AtomicInteger()

    /** `true`면 JWKS 요청에 500으로 응답합니다. */
    val jwksUnavailable = AtomicBoolean(false)

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                jwksRequestCount.incrementAndGet()
                val (status, body) =
                    if (jwksUnavailable.get()) {
                        500 to "{}"
                    } else {
                        200 to JWKSet(signingKey.toPublicJWK()).toString()
                    }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    val googleJwkSetUri: String = "http://127.0.0.1:${server.address.port}/google/jwks"
    val kakaoJwkSetUri: String = "http://127.0.0.1:${server.address.port}/kakao/jwks"
    val appleJwkSetUri: String = "http://127.0.0.1:${server.address.port}/apple/jwks"

    fun idToken(
        issuer: String?,
        audience: String?,
        subject: String?,
        expiresAt: Instant?,
        key: RSAKey = signingKey,
        extraClaims: Map<String, Any> = emptyMap(),
    ): String = sign(claims(issuer, audience?.let(::listOf), subject, expiresAt, extraClaims), key)

    /** `aud`를 배열로 담은 ID 토큰을 만듭니다. */
    fun idTokenWithAudiences(
        issuer: String,
        audiences: List<String>,
        subject: String,
        expiresAt: Instant,
    ): String = sign(claims(issuer, audiences, subject, expiresAt, emptyMap()), signingKey)

    private fun claims(
        issuer: String?,
        audiences: List<String>?,
        subject: String?,
        expiresAt: Instant?,
        extraClaims: Map<String, Any>,
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .audience(audiences)
                .subject(subject)
                .issueTime(Date.from(clock.instant().minusSeconds(10)))
                .expirationTime(expiresAt?.let(Date::from))
        extraClaims.forEach { (name, value) -> builder.claim(name, value) }
        return builder.build()
    }

    private fun sign(
        claims: JWTClaimsSet,
        key: RSAKey,
    ): String {
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .keyID(key.keyID)
                .type(JOSEObjectType.JWT)
                .build()
        val token = SignedJWT(header, claims)
        token.sign(RSASSASigner(key))
        return token.serialize()
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val GOOGLE_ISSUER = "https://accounts.google.com"
        const val KAKAO_ISSUER = "https://kauth.kakao.com"
        const val APPLE_ISSUER = "https://appleid.apple.com"
    }
}
