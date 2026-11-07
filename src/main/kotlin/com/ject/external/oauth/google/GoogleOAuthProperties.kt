package com.ject.external.oauth.google

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.oauth.google")
class GoogleOAuthProperties(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
) {
    init {
        require(clientId.isNotBlank()) { "Google OAuth client ID가 필요합니다." }
        require(clientSecret.isNotBlank()) { "Google OAuth client secret이 필요합니다." }
        require(redirectUri.isNotBlank()) { "Google OAuth redirect URI가 필요합니다." }
    }
}
