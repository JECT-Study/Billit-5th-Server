package com.ject.external.oauth.apple

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.oauth.apple")
class AppleOAuthProperties(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
) {
    init {
        require(clientId.isNotBlank()) { "Apple OAuth client ID가 필요합니다." }
        require(clientSecret.isNotBlank()) { "Apple OAuth client secret이 필요합니다." }
    }
}
