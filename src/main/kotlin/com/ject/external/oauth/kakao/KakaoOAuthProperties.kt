package com.ject.external.oauth.kakao

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.oauth.kakao")
class KakaoOAuthProperties(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
) {
    init {
        require(clientId.isNotBlank()) { "Kakao OAuth client ID가 필요합니다." }

        require(redirectUri.isNotBlank()) { "Kakao OAuth redirect URI가 필요합니다." }
    }
}
