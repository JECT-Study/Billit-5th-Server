package com.ject.module.auth.infra.client

import com.ject.external.apple.AppleTokenClient
import com.ject.external.apple.AppleTokenException
import com.ject.external.apple.AppleTokenFailureReason
import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AppleAuthorizationResult
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.infra.SocialLoginProperties
import com.ject.module.auth.usecase.port.AppleAuthorizationPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 애플 인가 코드 교환과 연결 해제를 [AppleTokenClient]로 호출하고, 애플의 실패를 인증 업무의 실패로 바꿉니다.
 *
 * - 인가 코드, 토큰과 client secret은 로그에 남기지 않습니다. 애플의 오류 코드(`error`)와 HTTP 상태만 남깁니다.
 * - 개인키가 설정되지 않았으면(local, test에서만 허용) 기동 시 WARN 로그를 한 번 남기고, 애플 호출 없이 제공자 장애(503)로 실패합니다.
 */
@Component
class AppleAuthorizationAdapter(
    properties: SocialLoginProperties,
    clock: Clock,
) : AppleAuthorizationPort {
    private val client =
        with(properties.apple) {
            AppleTokenClient(
                clientId = clientId,
                teamId = teamId,
                keyId = keyId,
                privateKey = privateKey,
                tokenUri = tokenUri,
                revokeUri = revokeUri,
                clock = clock,
            )
        }

    init {
        if (properties.apple.privateKey == null) {
            log.warn { "event=apple_private_key_missing impact=apple_login_unavailable" }
        }
    }

    override fun exchangeAuthorizationCode(authorizationCode: String): AppleAuthorizationResult {
        val tokens =
            try {
                client.exchangeAuthorizationCode(authorizationCode)
            } catch (exception: AppleTokenException) {
                throw exchangeFailure(exception)
            }
        return AppleAuthorizationResult(subject = tokens.subject, refreshToken = tokens.refreshToken)
    }

    override fun revokeRefreshToken(refreshToken: String) {
        try {
            client.revokeRefreshToken(refreshToken)
        } catch (exception: AppleTokenException) {
            if (exception.reason == AppleTokenFailureReason.REJECTED) {
                log.error { "event=apple_token_revoke_rejected status=${exception.statusCode} error=${exception.error}" }
            } else {
                logUnavailable("revoke", exception)
            }
            throw BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE)
        }
    }

    private fun exchangeFailure(exception: AppleTokenException): BusinessException {
        if (exception.reason != AppleTokenFailureReason.REJECTED) {
            logUnavailable("token", exception)
            return BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE)
        }
        val status = exception.statusCode
        val error = exception.error
        if (error == INVALID_GRANT) {
            log.info { "event=apple_authorization_code_rejected status=$status error=$error" }
            return BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED)
        }
        // invalid_grant 외의 거절은 서버 설정 문제일 수 있어 운영자가 확인하도록 남깁니다.
        log.error { "event=apple_authorization_code_rejected status=$status error=$error" }
        // 요청 한도 초과와 클라이언트 설정 오류는 앱이 소셜 인증을 다시 해도 해결되지 않는 서버 쪽 문제입니다.
        if (status == TOO_MANY_REQUESTS || error in SERVER_CONFIGURATION_ERRORS) {
            return BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE)
        }
        return BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED)
    }

    private fun logUnavailable(
        operation: String,
        exception: AppleTokenException,
    ) {
        when (exception.reason) {
            // 기동 시 WARN 로그를 남겼으므로 요청마다 다시 남기지 않습니다.
            AppleTokenFailureReason.CLIENT_SECRET_UNAVAILABLE -> {
                Unit
            }

            AppleTokenFailureReason.INVALID_RESPONSE -> {
                log.warn { "event=social_provider_unavailable provider=APPLE operation=$operation reason=invalid_response" }
            }

            else -> {
                log.warn {
                    "event=social_provider_unavailable provider=APPLE operation=$operation exceptionType=${exception.cause?.javaClass?.name}"
                }
            }
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}

        private const val INVALID_GRANT = "invalid_grant"
        private const val TOO_MANY_REQUESTS = 429

        /** RFC 6749 5.2의 오류 중 인가 코드가 아니라 서버의 클라이언트 설정이 원인인 것입니다. */
        private val SERVER_CONFIGURATION_ERRORS = setOf("invalid_client", "unauthorized_client", "unsupported_grant_type")
    }
}
