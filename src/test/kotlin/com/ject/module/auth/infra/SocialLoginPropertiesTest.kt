package com.ject.module.auth.infra

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Base64

class SocialLoginPropertiesTest :
    FunSpec({
        test("EC P-256 PKCS#8 PEM을 base64로 인코딩한 개인키는 client secret 서명 키로 사용한다") {
            // given
            val privateKeyBase64 = privateKeyBase64(Curve.P_256)

            // when
            val apple = apple(privateKeyBase64 = privateKeyBase64, privateKeyRequired = true)

            // then
            apple.privateKey.shouldNotBeNull()
        }

        test("EC P-384 개인키는 설정 생성에 실패한다") {
            // given
            val privateKeyBase64 = privateKeyBase64(Curve.P_384)

            // when
            val exception = shouldThrow<IllegalArgumentException> { apple(privateKeyBase64 = privateKeyBase64, privateKeyRequired = true) }

            // then
            exception.message shouldBe "애플 개인키는 EC P-256 키여야 합니다."
        }

        test("PEM 본문이 깨진 개인키는 설정 생성에 실패한다") {
            // given
            val privateKeyBase64 =
                Base64.getEncoder().encodeToString("-----BEGIN PRIVATE KEY-----\nbm90LWEta2V5\n-----END PRIVATE KEY-----\n".toByteArray())

            // when
            val exception = shouldThrow<IllegalArgumentException> { apple(privateKeyBase64 = privateKeyBase64, privateKeyRequired = false) }

            // then
            exception.message shouldBe "애플 개인키가 EC PKCS#8 형식이 아닙니다."
        }

        test("base64가 아닌 개인키는 설정 생성에 실패한다") {
            // given
            val privateKeyBase64 = "not-base64-key!"

            // when
            val exception = shouldThrow<IllegalArgumentException> { apple(privateKeyBase64 = privateKeyBase64, privateKeyRequired = false) }

            // then
            exception.message shouldBe "애플 개인키는 .p8 파일 내용을 base64로 인코딩해야 합니다."
        }

        test("개인키가 필수이면 빈 개인키는 설정 생성에 실패한다") {
            // given
            val privateKeyBase64 = " "

            // when
            val exception = shouldThrow<IllegalArgumentException> { apple(privateKeyBase64 = privateKeyBase64, privateKeyRequired = true) }

            // then
            exception.message shouldBe "애플 개인키가 비어 있습니다."
        }

        test("개인키가 필수가 아니면 빈 개인키를 허용하고 서명 키 없이 생성한다") {
            // given
            val privateKeyBase64 = ""

            // when
            val apple = apple(privateKeyBase64 = privateKeyBase64, privateKeyRequired = false)

            // then
            apple.privateKey.shouldBeNull()
        }

        context("애플 주소가 필수이면 토큰과 폐기 주소가 https://appleid.apple.com/으로 시작하지 않을 때 설정 생성에 실패한다") {
            val invalidUris =
                mapOf(
                    "토큰 주소가 로컬 http" to ("http://127.0.0.1:8080/auth/token" to APPLE_REVOKE_URI),
                    "토큰 주소가 비슷한 다른 호스트" to ("https://appleid.apple.com.example.com/auth/token" to APPLE_REVOKE_URI),
                    "폐기 주소가 http" to (APPLE_TOKEN_URI to "http://appleid.apple.com/auth/revoke"),
                )
            for ((case, uris) in invalidUris) {
                test(case) {
                    // given
                    val (tokenUri, revokeUri) = uris

                    // when
                    val exception =
                        shouldThrow<IllegalArgumentException> {
                            apple(tokenUri = tokenUri, revokeUri = revokeUri, appleEndpointRequired = true)
                        }

                    // then
                    exception.message.orEmpty().endsWith("https://appleid.apple.com/로 시작해야 합니다.") shouldBe true
                }
            }
        }

        test("애플 주소가 필수가 아니면 로컬 대역 서버 주소를 허용한다") {
            // given
            val tokenUri = "http://127.0.0.1:8080/auth/token"

            // when
            val apple = apple(tokenUri = tokenUri, revokeUri = "http://127.0.0.1:8080/auth/revoke", appleEndpointRequired = false)

            // then
            apple.tokenUri shouldBe tokenUri
        }
    }) {
    companion object {
        private const val APPLE_TOKEN_URI = "https://appleid.apple.com/auth/token"
        private const val APPLE_REVOKE_URI = "https://appleid.apple.com/auth/revoke"

        private fun apple(
            privateKeyBase64: String = "",
            privateKeyRequired: Boolean = false,
            tokenUri: String = APPLE_TOKEN_URI,
            revokeUri: String = APPLE_REVOKE_URI,
            appleEndpointRequired: Boolean = true,
        ) = SocialLoginProperties.Apple(
            clientId = "app.billit.mobile.test",
            teamId = "TEAMID1234",
            keyId = "test-apple-key-id",
            privateKeyBase64 = privateKeyBase64,
            privateKeyRequired = privateKeyRequired,
            jwkSetUri = "https://appleid.apple.com/auth/keys",
            tokenUri = tokenUri,
            revokeUri = revokeUri,
            appleEndpointRequired = appleEndpointRequired,
        )

        /** 애플 `.p8`과 같은 PKCS#8 PEM을 만든 뒤 `APPLE_PRIVATE_KEY_BASE64` 형식으로 base64 인코딩합니다. */
        private fun privateKeyBase64(curve: Curve): String {
            val encoded = ECKeyGenerator(curve).generate().toECPrivateKey().encoded
            val pem = "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder(
                64,
                "\n".toByteArray(),
            ).encodeToString(encoded)}\n-----END PRIVATE KEY-----\n"
            return Base64.getEncoder().encodeToString(pem.toByteArray())
        }
    }
}
