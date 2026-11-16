package com.ject.module.auth.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("app.auth.token-encryption")
class TokenEncryptionProperties(
    private val key: String,
) {
    /** 외부 제공자 토큰을 저장할 때 쓰는 AES-256 키. 환경 변수에는 32바이트 임의 값을 base64로 인코딩해 넣습니다. */
    val secretKey: SecretKey

    init {
        val keyBytes =
            try {
                Base64.getDecoder().decode(key.filterNot { it.isWhitespace() })
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("토큰 암호화 키는 base64로 인코딩해야 합니다.", exception)
            }
        require(keyBytes.size == KEY_BYTES) { "토큰 암호화 키는 ${KEY_BYTES}바이트여야 합니다." }
        secretKey = SecretKeySpec(keyBytes, "AES")
    }

    companion object {
        private const val KEY_BYTES = 32
    }
}
