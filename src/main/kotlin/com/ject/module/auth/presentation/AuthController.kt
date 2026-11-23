package com.ject.module.auth.presentation

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.global.response.ApiResponse
import com.ject.module.auth.domain.AuthTokenClaims
import com.ject.module.auth.domain.SocialLoginResult
import com.ject.module.auth.presentation.request.LogoutRequest
import com.ject.module.auth.presentation.request.SocialLoginRequest
import com.ject.module.auth.presentation.request.TokenRefreshRequest
import com.ject.module.auth.presentation.response.AuthenticatedSocialLoginResponse
import com.ject.module.auth.presentation.response.RegistrationRequiredSocialLoginResponse
import com.ject.module.auth.presentation.response.SocialLoginResponse
import com.ject.module.auth.presentation.response.SocialLoginStatus
import com.ject.module.auth.presentation.response.TokenRefreshResponse
import com.ject.module.auth.usecase.LogoutUseCase
import com.ject.module.auth.usecase.RefreshTokensUseCase
import com.ject.module.auth.usecase.SocialLoginUseCase
import com.ject.module.member.domain.AuthProvider
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val socialLoginUseCase: SocialLoginUseCase,
    private val refreshTokensUseCase: RefreshTokensUseCase,
    private val logoutUseCase: LogoutUseCase,
) {
    @PostMapping("/social-logins")
    fun socialLogin(
        @Valid @RequestBody request: SocialLoginRequest,
    ): ApiResponse<SocialLoginResponse> {
        val result =
            if (request.provider == AuthProvider.APPLE) {
                val authorizationCode = request.authorizationCode ?: throw BusinessException(CommonErrorCode.INVALID_REQUEST)
                socialLoginUseCase.loginWithApple(request.idToken, authorizationCode)
            } else {
                // 구글과 카카오 요청 형식에는 애플 전용 필드가 없습니다.
                if (request.authorizationCode != null || request.fullName != null) {
                    throw BusinessException(CommonErrorCode.INVALID_REQUEST)
                }
                socialLoginUseCase.login(request.provider, request.idToken)
            }

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

    @PostMapping("/token-refreshes")
    fun refreshTokens(
        @Valid @RequestBody request: TokenRefreshRequest,
    ): ApiResponse<TokenRefreshResponse> {
        val tokens = refreshTokensUseCase.refresh(request.refreshToken)

        return ApiResponse(TokenRefreshResponse(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken))
    }

    /** Access Token이 있으면 헤더의 세션을, 없으면 본문의 Refresh Token이 가리키는 세션을 종료합니다. 둘 다 오면 헤더를 따릅니다. */
    @PostMapping("/logout")
    fun logout(
        @AuthenticationPrincipal accessToken: Jwt?,
        @Valid @RequestBody(required = false) request: LogoutRequest?,
    ): ResponseEntity<Void> {
        if (accessToken != null) {
            val sessionId = UUID.fromString(checkNotNull(accessToken.getClaimAsString(AuthTokenClaims.SESSION_ID)))
            logoutUseCase.logoutByAccessToken(sessionId)
        } else if (request != null) {
            logoutUseCase.logoutByRefreshToken(request.refreshToken)
        } else {
            throw BusinessException(CommonErrorCode.UNAUTHORIZED)
        }

        return ResponseEntity.noContent().build()
    }
}
