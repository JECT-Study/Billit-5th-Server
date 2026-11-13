package com.ject.module.auth.infra

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Base64

class AuthTokenPropertiesTest :
    FunSpec({
        test("64바이트 서명 키는 HS512 키로 사용한다") {
            // given
            val secret = Base64.getEncoder().encodeToString(ByteArray(64) { 1 })

            // when
            val properties = AuthTokenProperties(secret = secret)

            // then
            properties.signingKey.algorithm shouldBe "HmacSHA512"
        }

        test("64바이트보다 짧은 서명 키는 설정 생성에 실패한다") {
            // given
            val secret = Base64.getEncoder().encodeToString(ByteArray(63) { 1 })

            // when
            val exception = shouldThrow<IllegalArgumentException> { AuthTokenProperties(secret = secret) }

            // then
            exception.message shouldBe "JWT 서명 키는 64바이트 이상이어야 합니다."
        }

        test("base64가 아닌 서명 키는 설정 생성에 실패한다") {
            // given
            val secret = "not-base64-secret!"

            // when
            val exception = shouldThrow<IllegalArgumentException> { AuthTokenProperties(secret = secret) }

            // then
            exception.message shouldBe "JWT 서명 키는 base64로 인코딩해야 합니다."
        }
    })
