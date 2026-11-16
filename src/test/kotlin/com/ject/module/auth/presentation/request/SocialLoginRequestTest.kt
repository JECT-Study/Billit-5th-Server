package com.ject.module.auth.presentation.request

import com.ject.module.member.domain.AuthProvider
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import jakarta.validation.Validation

class SocialLoginRequestTest :
    FunSpec({
        val validator = Validation.buildDefaultValidatorFactory().validator

        fun fullNameValid(fullName: String): Boolean =
            validator
                .validate(SocialLoginRequest(AuthProvider.APPLE, idToken = "token", authorizationCode = "code", fullName = fullName))
                .none { it.propertyPath.toString() == "fullName" }

        test("fullName은 이모지 같은 보조 평면 문자도 한 글자로 세어 100자까지 받는다") {
            // given
            val emoji = "😀"

            // when
            val results = listOf(emoji.repeat(100), emoji.repeat(101), "가".repeat(100), "가".repeat(101), " ").map(::fullNameValid)

            // then
            results shouldBe listOf(true, false, true, false, false)
        }
    })
