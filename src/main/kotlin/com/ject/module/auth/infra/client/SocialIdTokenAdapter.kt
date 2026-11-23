package com.ject.module.auth.infra.client

import com.ject.external.socialidtoken.IdTokenFailureReason
import com.ject.external.socialidtoken.IdTokenVerificationException
import com.ject.external.socialidtoken.IdTokenVerifier
import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.domain.SocialIdentity
import com.ject.module.auth.infra.SocialLoginProperties
import com.ject.module.auth.usecase.port.SocialIdTokenPort
import com.ject.module.member.domain.AuthProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 구글, 카카오와 애플 ID 토큰을 제공자별 [IdTokenVerifier]로 검증하고 결과를 인증 업무의 실패로 바꿉니다.
 *
 * 유효하지 않은 토큰은 인증 실패(401), 제공자 JWKS를 가져오지 못한 경우는 제공자 장애(503)입니다. JWKS 캐시와 재조회 제한은 [IdTokenVerifier]를 따릅니다.
 */
@Component
class SocialIdTokenAdapter(
    properties: SocialLoginProperties,
    clock: Clock,
) : SocialIdTokenPort {
    private val googleVerifier = IdTokenVerifier(properties.google.jwkSetUri, GOOGLE_ISSUERS, properties.google.clientId, clock)
    private val kakaoVerifier = IdTokenVerifier(properties.kakao.jwkSetUri, KAKAO_ISSUERS, properties.kakao.nativeAppKey, clock)
    private val appleVerifier = IdTokenVerifier(properties.apple.jwkSetUri, APPLE_ISSUERS, properties.apple.clientId, clock)

    override fun verify(
        provider: AuthProvider,
        idToken: String,
    ): SocialIdentity {
        val verifier =
            when (provider) {
                AuthProvider.GOOGLE -> googleVerifier
                AuthProvider.KAKAO -> kakaoVerifier
                AuthProvider.APPLE -> appleVerifier
            }
        val token =
            try {
                verifier.verify(idToken)
            } catch (exception: IdTokenVerificationException) {
                when (exception.reason) {
                    IdTokenFailureReason.INVALID_TOKEN -> {
                        throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED, exception)
                    }

                    IdTokenFailureReason.KEY_SOURCE_UNAVAILABLE -> {
                        log.warn(exception) { "event=social_provider_unavailable provider=$provider" }
                        throw BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE, exception)
                    }
                }
            }
        return SocialIdentity(subject = token.subject)
    }

    companion object {
        private val log = KotlinLogging.logger {}

        private val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
        private val KAKAO_ISSUERS = setOf("https://kauth.kakao.com")
        private val APPLE_ISSUERS = setOf("https://appleid.apple.com")
    }
}
