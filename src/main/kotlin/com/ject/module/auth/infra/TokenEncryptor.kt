package com.ject.module.auth.infra

import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * 외부 제공자 토큰을 AES-256-GCM으로 암호화합니다.
 *
 * 암호문은 base64(12바이트 임의 nonce + 암호문과 16바이트 인증 태그)입니다. [associatedData]는 암호문을 다른 행으로 옮겨 쓰지 못하도록 저장 위치를 묶는 값이며 복호화할 때도 같아야 합니다.
 */
@Component
class TokenEncryptor(
    properties: TokenEncryptionProperties,
) {
    private val secretKey = properties.secretKey
    private val secureRandom = SecureRandom()

    fun encrypt(
        plaintext: String,
        associatedData: String,
    ): String {
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData.toByteArray())
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plaintext.toByteArray()))
    }

    /**
     * 키나 [associatedData]가 다르거나 암호문이 변조됐으면 `javax.crypto.AEADBadTagException`으로 실패합니다.
     * 암호문이 base64가 아니거나 nonce보다 짧으면 [IllegalArgumentException]으로 실패합니다.
     */
    fun decrypt(
        ciphertext: String,
        associatedData: String,
    ): String {
        val bytes = Base64.getDecoder().decode(ciphertext)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
        cipher.updateAAD(associatedData.toByteArray())
        return String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES))
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
    }
}
