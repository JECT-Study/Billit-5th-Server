package com.ject.module.auth.infra

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Base64
import javax.crypto.AEADBadTagException

class TokenEncryptorTest :
    FunSpec({
        val encryptor = TokenEncryptor(TokenEncryptionProperties(key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })))

        test("같은 인증 데이터로 복호화하면 원문을 돌려준다") {
            // given
            val ciphertext = encryptor.encrypt("apple-refresh-token", "apple_refresh_token:1")

            // when
            val plaintext = encryptor.decrypt(ciphertext, "apple_refresh_token:1")

            // then
            plaintext shouldBe "apple-refresh-token"
        }

        test("인증 데이터가 다르면 복호화에 실패한다") {
            // given
            val ciphertext = encryptor.encrypt("apple-refresh-token", "apple_refresh_token:1")

            // when, then
            shouldThrow<AEADBadTagException> { encryptor.decrypt(ciphertext, "apple_refresh_token:2") }
        }

        test("암호문의 한 바이트라도 바뀌면 복호화에 실패한다") {
            // given
            val bytes = Base64.getDecoder().decode(encryptor.encrypt("apple-refresh-token", "apple_refresh_token:1"))
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
            val tampered = Base64.getEncoder().encodeToString(bytes)

            // when, then
            shouldThrow<AEADBadTagException> { encryptor.decrypt(tampered, "apple_refresh_token:1") }
        }

        test("같은 원문을 다시 암호화하면 12바이트 nonce와 암호문이 매번 다르다") {
            // given
            val plaintext = "apple-refresh-token"

            // when
            val first = Base64.getDecoder().decode(encryptor.encrypt(plaintext, "apple_refresh_token:1"))
            val second = Base64.getDecoder().decode(encryptor.encrypt(plaintext, "apple_refresh_token:1"))

            // then
            assertSoftly {
                first.copyOfRange(0, 12).toList() shouldNotBe second.copyOfRange(0, 12).toList()
                first.toList() shouldNotBe second.toList()
            }
        }
    })
