package com.ject.module.auth.infra.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AppleAuthorizationResult
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.infra.SocialLoginProperties
import com.ject.module.auth.usecase.port.AppleAuthorizationPort
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.JWTParser
import com.nimbusds.jwt.SignedJWT
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.util.Date

/**
 * 애플 토큰 API(`/auth/token`, `/auth/revoke`)를 호출합니다.
 *
 * - client secret은 요청마다 ES256 JWT로 만듭니다. 헤더 `kid`는 키 ID, `iss`는 팀 ID, `sub`는 클라이언트 ID, `aud`는 `https://appleid.apple.com`이며 [CLIENT_SECRET_TTL] 뒤 만료됩니다.
 * - 연결 2초, 응답 3초로 제한합니다. 재시도하지 않습니다. 인가 코드는 한 번만 교환할 수 있어 같은 코드로 다시 보내면 애플이 거절합니다.
 * - 인가 코드, 토큰과 client secret은 로그에 남기지 않습니다. 애플의 오류 코드(`error`)와 HTTP 상태만 남깁니다.
 */
@Component
class AppleAuthorizationAdapter(
    properties: SocialLoginProperties,
    private val clock: Clock,
) : AppleAuthorizationPort {
    private val apple = properties.apple
    private val restClient =
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()).apply {
                    setReadTimeout(READ_TIMEOUT)
                },
            ).build()

    override fun exchangeAuthorizationCode(authorizationCode: String): AppleAuthorizationResult {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("code", authorizationCode)
                add("client_id", apple.clientId)
                add("client_secret", createClientSecret())
            }
        val response =
            try {
                post(apple.tokenUri, form).body(AppleTokenResponse::class.java)
            } catch (exception: HttpClientErrorException) {
                val error = errorCode(exception)
                if (error == INVALID_GRANT) {
                    log.info { "event=apple_authorization_code_rejected status=${exception.statusCode.value()} error=$error" }
                } else {
                    // invalid_client 같은 오류는 서버 설정 문제일 수 있어 운영자가 확인하도록 남깁니다.
                    log.error { "event=apple_authorization_code_rejected status=${exception.statusCode.value()} error=$error" }
                }
                throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED)
            } catch (exception: RestClientException) {
                log.warn { "event=social_provider_unavailable provider=APPLE operation=token exceptionType=${exception.javaClass.name}" }
                throw BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE)
            } ?: throw unavailableResponse("token")

        return AppleAuthorizationResult(
            subject = response.idToken?.let(::subjectOf),
            refreshToken = response.refreshToken?.takeIf { it.isNotEmpty() },
        )
    }

    override fun revokeRefreshToken(refreshToken: String) {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("token", refreshToken)
                add("token_type_hint", "refresh_token")
                add("client_id", apple.clientId)
                add("client_secret", createClientSecret())
            }
        try {
            post(apple.revokeUri, form).toBodilessEntity()
        } catch (exception: HttpClientErrorException) {
            log.error { "event=apple_token_revoke_rejected status=${exception.statusCode.value()} error=${errorCode(exception)}" }
            throw BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE)
        } catch (exception: RestClientException) {
            log.warn { "event=social_provider_unavailable provider=APPLE operation=revoke exceptionType=${exception.javaClass.name}" }
            throw BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE)
        }
    }

    private fun post(
        uri: String,
        form: LinkedMultiValueMap<String, String>,
    ): RestClient.ResponseSpec =
        restClient
            .post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(MediaType.APPLICATION_JSON)
            .body(form)
            .retrieve()

    private fun createClientSecret(): String {
        val privateKey = checkNotNull(apple.privateKey) { "APPLE_PRIVATE_KEY_BASE64가 설정되지 않아 애플 client secret을 만들 수 없습니다." }
        val now = clock.instant()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(apple.teamId)
                .subject(apple.clientId)
                .audience(CLIENT_SECRET_AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(CLIENT_SECRET_TTL)))
                .build()
        val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(apple.keyId).build(), claims)
        token.sign(ECDSASigner(privateKey))
        return token.serialize()
    }

    /**
     * 교환 응답의 ID 토큰에서 `sub`만 읽습니다.
     *
     * 이 응답은 서버가 TLS로 애플에 직접 받은 것이라 서명은 다시 확인하지 않습니다. 사용자 확인은 요청으로 받은 ID 토큰을 JWKS로 검증해 끝냈고, 여기서는 같은 사용자인지만 비교합니다.
     */
    private fun subjectOf(idToken: String): String =
        try {
            JWTParser.parse(idToken).jwtClaimsSet.subject
        } catch (_: ParseException) {
            null
        } ?: throw unavailableResponse("token")

    private fun errorCode(exception: HttpClientErrorException): String? =
        try {
            exception.getResponseBodyAs(AppleErrorResponse::class.java)?.error
        } catch (_: RuntimeException) {
            null
        }

    private fun unavailableResponse(operation: String): BusinessException {
        log.warn { "event=social_provider_unavailable provider=APPLE operation=$operation reason=invalid_response" }
        return BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AppleTokenResponse(
        @param:JsonProperty("refresh_token") val refreshToken: String?,
        @param:JsonProperty("id_token") val idToken: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class AppleErrorResponse(
        val error: String?,
    )

    companion object {
        private val log = KotlinLogging.logger {}

        private const val INVALID_GRANT = "invalid_grant"
        private const val CLIENT_SECRET_AUDIENCE = "https://appleid.apple.com"
        private val CLIENT_SECRET_TTL: Duration = Duration.ofMinutes(5)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}
