package com.ject.module.auth.infra

import org.springframework.boot.context.properties.ConfigurationProperties

/** 소셜 ID 토큰 검증에 쓰는 제공자별 설정입니다. JWKS 주소는 테스트에서 바꿀 수 있도록 설정으로 둡니다. */
@ConfigurationProperties("app.auth.social")
class SocialLoginProperties(
    val google: Google,
    val kakao: Kakao,
) {
    /** [clientId]는 ID 토큰의 `aud`로 받아들일 웹 클라이언트 ID입니다. 환경마다 하나만 받습니다. */
    class Google(
        val clientId: String,
        val jwkSetUri: String,
    ) {
        init {
            require(clientId.isNotBlank()) { "구글 클라이언트 ID가 비어 있습니다." }
        }
    }

    /** [nativeAppKey]는 ID 토큰의 `aud`로 받아들일 카카오 네이티브 앱 키입니다. */
    class Kakao(
        val nativeAppKey: String,
        val jwkSetUri: String,
    ) {
        init {
            require(nativeAppKey.isNotBlank()) { "카카오 네이티브 앱 키가 비어 있습니다." }
        }
    }
}
