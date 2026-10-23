package com.ject.module.auth.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.auth.jwt")
class JwtProperties(
    val secret: String,
    val issuer: String,
    val accessTokenTtl: Duration,
) {
    init {
        require(secret.toByteArray(Charsets.UTF_8).size >= 32) { "JWT secret must contain at least 32 bytes" }
        require(issuer.isNotBlank()) { "JWT issuer must not be blank" }
        require(accessTokenTtl >= Duration.ofSeconds(1)) { "JWT access token TTL must be at least one second" }
    }
}
