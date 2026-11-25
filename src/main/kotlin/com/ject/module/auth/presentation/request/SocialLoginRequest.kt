package com.ject.module.auth.presentation.request

import com.ject.module.member.domain.AuthProvider
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * 소셜 로그인 요청입니다. 명세의 `SocialIdTokenRequest`(구글, 카카오)와 `AppleSocialLoginRequest`(애플)를 함께 받습니다.
 *
 * 애플은 [authorizationCode]가 필수입니다. [fullName]은 Client가 아직 보내므로 받기만 하고 저장하지 않습니다(Client가 보내지 않게 되면 지웁니다). 구글과 카카오 요청에 두 필드가 있으면 거절합니다. 이 조합은 Controller가 확인합니다.
 */
data class SocialLoginRequest(
    val provider: AuthProvider,
    @field:NotBlank
    val idToken: String,
    @field:Pattern(regexp = NOT_BLANK_PATTERN)
    val authorizationCode: String? = null,
    @field:Pattern(regexp = FULL_NAME_PATTERN)
    val fullName: String? = null,
) {
    companion object {
        /** 공백이 아닌 문자를 하나 이상 포함합니다. 값이 없으면 검사하지 않습니다. */
        private const val NOT_BLANK_PATTERN = "(?s).*\\S.*"

        /**
         * 공백이 아닌 문자를 포함하고 [FULL_NAME_MAX_LENGTH]자 이하입니다(기존 명세 `maxLength`).
         * 정규식의 `.`은 코드 포인트 하나에 맞으므로 이모지 같은 보조 평면 문자도 한 글자로 셉니다(명세 `maxLength`, DB `VARCHAR`와 같은 기준).
         */
        private const val FULL_NAME_MAX_LENGTH = 100
        private const val FULL_NAME_PATTERN = "(?s)(?=.*\\S).{1,$FULL_NAME_MAX_LENGTH}"
    }
}
