package com.ject.module.auth.infra

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.crypto.MACVerifier
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.BadJWTException
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import java.time.Clock
import java.util.Date

class JwtAccessTokenCodec(
    private val properties: JwtProperties,
    private val clock: Clock,
) {
    private val secret = properties.secret.toByteArray(Charsets.UTF_8)
    private val claimsVerifier =
        object : DefaultJWTClaimsVerifier<SecurityContext>(
            JWTClaimsSet.Builder().issuer(properties.issuer).build(),
            setOf("iss", "sub", "iat", "exp"),
        ) {
            override fun currentTime(): Date = Date.from(clock.instant())
        }.apply {
            maxClockSkew = 0
        }

    fun issue(userId: String): String {
        require(userId.isNotBlank()) { "User ID must not be blank" }
        val now = clock.instant()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(properties.issuer)
                .subject(userId)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(properties.accessTokenTtl)))
                .build()
        val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims)
        token.sign(MACSigner(secret))
        return token.serialize()
    }

    fun verify(accessToken: String): String {
        val token = SignedJWT.parse(accessToken)
        if (token.header.algorithm != JWSAlgorithm.HS256 || !token.verify(MACVerifier(secret))) {
            throw BadJWTException("Invalid access token signature")
        }
        val claims = token.jwtClaimsSet
        claimsVerifier.verify(claims, null)
        val userId = claims.subject
        if (userId.isNullOrBlank()) {
            throw BadJWTException("Missing access token subject")
        }
        return userId
    }
}
