package com.ject.module.auth.infra

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Base64

class TokenEncryptionPropertiesTest :
    FunSpec({
        test("32바이트 키는 AES 키로 사용한다") {
            // given
            val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })

            // when
            val properties = TokenEncryptionProperties(key = key)

            // then
            properties.secretKey.algorithm shouldBe "AES"
        }

        context("32바이트가 아닌 키는 설정 생성에 실패한다") {
            for (size in listOf(31, 33)) {
                test("${size}바이트") {
                    // given
                    val key = Base64.getEncoder().encodeToString(ByteArray(size) { 1 })

                    // when
                    val exception = shouldThrow<IllegalArgumentException> { TokenEncryptionProperties(key = key) }

                    // then
                    exception.message shouldBe "토큰 암호화 키는 32바이트여야 합니다."
                }
            }
        }

        test("base64가 아닌 키는 설정 생성에 실패한다") {
            // given
            val key = "not-base64-key!"

            // when
            val exception = shouldThrow<IllegalArgumentException> { TokenEncryptionProperties(key = key) }

            // then
            exception.message shouldBe "토큰 암호화 키는 base64로 인코딩해야 합니다."
        }
    })
