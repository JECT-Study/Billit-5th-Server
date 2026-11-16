package com.ject.external.apple

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.JWTParser
import com.nimbusds.jwt.SignedJWT
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.http.HttpClient
import java.security.interfaces.ECPrivateKey
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.util.Date

/**
 * 애플 토큰 API(`/auth/token`, `/auth/revoke`)를 호출합니다.
 *
 * - client secret은 요청마다 ES256 JWT로 만듭니다. 헤더 `kid`는 키 ID, `iss`는 팀 ID, `sub`는 클라이언트 ID, `aud`는 `https://appleid.apple.com`이며 [CLIENT_SECRET_TTL] 뒤 만료됩니다.
 * - 연결 2초, 응답 3초로 제한합니다. 재시도하지 않습니다. 인가 코드는 한 번만 교환할 수 있어 같은 코드로 다시 보내면 애플이 거절합니다.
 * - 실패는 [AppleTokenException]으로 던지며, 인가 코드, 토큰과 client secret은 예외에 담지 않습니다.
 */
class AppleTokenClient(
    private val clientId: String,
    private val teamId: String,
    private val keyId: String,
    private val privateKey: ECPrivateKey?,
    private val tokenUri: String,
    private val revokeUri: String,
    private val clock: Clock,
) {
    private val restClient =
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()).apply {
                    setReadTimeout(READ_TIMEOUT)
                },
            ).build()

    fun exchangeAuthorizationCode(authorizationCode: String): AppleTokens {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("grant_type", "authorization_code")
                add("code", authorizationCode)
                add("client_id", clientId)
                add("client_secret", createClientSecret())
            }
        val response =
            call { post(tokenUri, form).body(AppleTokenResponse::class.java) }
                ?: throw AppleTokenException(AppleTokenFailureReason.INVALID_RESPONSE)
        return AppleTokens(
            subject = subjectOf(response.idToken),
            refreshToken = response.refreshToken?.takeIf { it.isNotEmpty() },
        )
    }

    /** 애플 Refresh Token을 폐기합니다. 이미 폐기된 토큰도 애플은 성공으로 응답합니다. */
    fun revokeRefreshToken(refreshToken: String) {
        val form =
            LinkedMultiValueMap<String, String>().apply {
                add("token", refreshToken)
                add("token_type_hint", "refresh_token")
                add("client_id", clientId)
                add("client_secret", createClientSecret())
            }
        call { post(revokeUri, form).toBodilessEntity() }
    }

    private fun <T> call(request: () -> T): T =
        try {
            request()
        } catch (exception: HttpClientErrorException) {
            throw AppleTokenException(AppleTokenFailureReason.REJECTED, exception.statusCode.value(), errorCode(exception), exception)
        } catch (exception: RestClientException) {
            throw AppleTokenException(AppleTokenFailureReason.UNAVAILABLE, cause = exception)
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
        val key = privateKey ?: throw AppleTokenException(AppleTokenFailureReason.CLIENT_SECRET_UNAVAILABLE)
        val now = clock.instant()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(teamId)
                .subject(clientId)
                .audience(CLIENT_SECRET_AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(CLIENT_SECRET_TTL)))
                .build()
        val token = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims)
        token.sign(ECDSASigner(key))
        return token.serialize()
    }

    /**
     * 교환 응답의 ID 토큰에서 `sub`만 읽습니다.
     *
     * 이 응답은 서버가 TLS로 애플에 직접 받은 것이라 서명은 다시 확인하지 않습니다. 사용자 확인은 요청으로 받은 ID 토큰을 JWKS로 검증해 끝냈고, 호출한 쪽은 같은 사용자인지만 비교합니다.
     */
    private fun subjectOf(idToken: String?): String =
        try {
            idToken?.let { JWTParser.parse(it).jwtClaimsSet.subject }
        } catch (_: ParseException) {
            null
        }?.takeIf { it.isNotBlank() } ?: throw AppleTokenException(AppleTokenFailureReason.INVALID_RESPONSE)

    private fun errorCode(exception: HttpClientErrorException): String? =
        try {
            exception.getResponseBodyAs(AppleErrorResponse::class.java)?.error
        } catch (_: RuntimeException) {
            null
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
        private const val CLIENT_SECRET_AUDIENCE = "https://appleid.apple.com"
        private val CLIENT_SECRET_TTL: Duration = Duration.ofMinutes(5)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
    }
}
