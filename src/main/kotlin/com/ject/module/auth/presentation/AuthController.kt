package com.ject.module.auth.presentation

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.global.response.ApiResponse
import com.ject.module.auth.domain.SocialLoginResult
import com.ject.module.auth.presentation.request.SocialLoginRequest
import com.ject.module.auth.presentation.response.AuthenticatedSocialLoginResponse
import com.ject.module.auth.presentation.response.RegistrationRequiredSocialLoginResponse
import com.ject.module.auth.presentation.response.SocialLoginResponse
import com.ject.module.auth.presentation.response.SocialLoginStatus
import com.ject.module.auth.usecase.SocialLoginUseCase
import com.ject.module.member.domain.AuthProvider
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val socialLoginUseCase: SocialLoginUseCase,
) {
    @PostMapping("/social-logins")
    fun socialLogin(
        @Valid @RequestBody request: SocialLoginRequest,
    ): ApiResponse<SocialLoginResponse> {
        // Apple은 인가 코드가 필수인 별도 요청 형식이라 ID 토큰만으로는 받지 않습니다.
        if (request.provider == AuthProvider.APPLE) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }

        val result = socialLoginUseCase.login(request.provider, request.idToken)

        val response =
            when (result) {
                is SocialLoginResult.Authenticated -> {
                    AuthenticatedSocialLoginResponse(
                        status = SocialLoginStatus.AUTHENTICATED,
                        accessToken = result.tokens.accessToken,
                        refreshToken = result.tokens.refreshToken,
                    )
                }

                is SocialLoginResult.RegistrationRequired -> {
                    RegistrationRequiredSocialLoginResponse(
                        status = SocialLoginStatus.REGISTRATION_REQUIRED,
                        registrationToken = result.registrationToken,
                    )
                }
            }
        return ApiResponse(response)
    }
}
