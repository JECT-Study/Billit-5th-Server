package com.ject.module.auth.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("app.auth.jwt")
class AuthTokenProperties(
    private val secret: String,
) {
    /** HS512 서명 키. 환경 변수에는 64바이트 이상의 값을 base64로 인코딩해 넣습니다. */
    val signingKey: SecretKey

    init {
        val keyBytes =
            try {
                Base64.getDecoder().decode(secret.filterNot { it.isWhitespace() })
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("JWT 서명 키는 base64로 인코딩해야 합니다.", exception)
            }
        require(keyBytes.size >= MIN_SECRET_KEY_BYTES) { "JWT 서명 키는 ${MIN_SECRET_KEY_BYTES}바이트 이상이어야 합니다." }
        signingKey = SecretKeySpec(keyBytes, "HmacSHA512")
    }

    companion object {
        private const val MIN_SECRET_KEY_BYTES = 64
    }
}
