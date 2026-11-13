package com.ject.module.auth.infra

import com.nimbusds.jose.jwk.Curve
import org.springframework.boot.context.properties.ConfigurationProperties
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** 소셜 ID 토큰 검증과 애플 인가 코드 교환에 쓰는 제공자별 설정입니다. 제공자 주소는 테스트에서 바꿀 수 있도록 설정으로 둡니다. */
@ConfigurationProperties("app.auth.social")
class SocialLoginProperties(
    val google: Google,
    val kakao: Kakao,
    val apple: Apple,
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

    /**
     * [clientId]는 ID 토큰의 `aud`이자 client secret의 `sub`인 앱 번들 ID, [teamId]와 [keyId]는 client secret의 `iss`와 헤더 `kid`입니다.
     *
     * [privateKeyBase64]는 애플에서 받은 `.p8` 파일 전체를 base64로 한 줄로 만든 값입니다. 비어 있으면 인가 코드를 교환할 수 없어 애플 로그인이 실패합니다.
     */
    class Apple(
        val clientId: String,
        val teamId: String,
        val keyId: String,
        privateKeyBase64: String,
        val jwkSetUri: String,
        val tokenUri: String,
        val revokeUri: String,
    ) {
        /** client secret 서명에 쓰는 EC P-256 개인키입니다. 설정하지 않았으면 `null`입니다. */
        val privateKey: ECPrivateKey? = privateKeyBase64.takeIf { it.isNotBlank() }?.let(::parsePrivateKey)

        init {
            require(clientId.isNotBlank()) { "애플 클라이언트 ID가 비어 있습니다." }
            require(teamId.isNotBlank()) { "애플 팀 ID가 비어 있습니다." }
            require(keyId.isNotBlank()) { "애플 키 ID가 비어 있습니다." }
        }

        private fun parsePrivateKey(privateKeyBase64: String): ECPrivateKey {
            val pem =
                try {
                    String(Base64.getDecoder().decode(privateKeyBase64.filterNot { it.isWhitespace() }))
                } catch (exception: IllegalArgumentException) {
                    throw IllegalArgumentException("애플 개인키는 .p8 파일 내용을 base64로 인코딩해야 합니다.", exception)
                }
            val pkcs8 =
                pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .filterNot { it.isWhitespace() }
            val key =
                try {
                    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8)))
                } catch (exception: IllegalArgumentException) {
                    throw IllegalArgumentException("애플 개인키가 PKCS#8 PEM 형식이 아닙니다.", exception)
                } catch (exception: InvalidKeySpecException) {
                    throw IllegalArgumentException("애플 개인키가 EC PKCS#8 형식이 아닙니다.", exception)
                }
            require(key is ECPrivateKey && Curve.forECParameterSpec(key.params) == Curve.P_256) { "애플 개인키는 EC P-256 키여야 합니다." }
            return key
        }
    }
}
