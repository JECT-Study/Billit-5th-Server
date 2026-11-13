package com.ject.module.auth.presentation.request

import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.MemberRepository
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/**
 * 소셜 로그인 요청입니다. 명세의 `SocialIdTokenRequest`(구글, 카카오)와 `AppleSocialLoginRequest`(애플)를 함께 받습니다.
 *
 * 애플은 [authorizationCode]가 필수이고 [fullName]은 선택입니다. [fullName]은 저장 컬럼 길이에 맞춰 100자까지 받습니다. 구글과 카카오 요청에 두 필드가 있으면 거절합니다. 이 조합은 Controller가 확인합니다.
 */
data class SocialLoginRequest(
    val provider: AuthProvider,
    @field:NotBlank
    val idToken: String,
    @field:Pattern(regexp = NOT_BLANK_PATTERN)
    val authorizationCode: String? = null,
    @field:Pattern(regexp = NOT_BLANK_PATTERN)
    @field:Size(max = MemberRepository.SOCIAL_DISPLAY_NAME_MAX_LENGTH)
    val fullName: String? = null,
) {
    companion object {
        /** 공백이 아닌 문자를 하나 이상 포함합니다. 값이 없으면 검사하지 않습니다. */
        private const val NOT_BLANK_PATTERN = "(?s).*\\S.*"
    }
}
