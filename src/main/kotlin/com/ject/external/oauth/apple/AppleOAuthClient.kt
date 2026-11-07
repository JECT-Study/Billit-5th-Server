package com.ject.external.oauth.apple

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.ject.external.oauth.OAuthClientException
import com.ject.external.oauth.OAuthIdentity
import com.ject.external.oauth.OidcIdTokenVerifier
import com.ject.external.oauth.oauthRequest
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

class AppleOAuthClient(
    private val restClient: RestClient,
    private val properties: AppleOAuthProperties,
    private val idTokenVerifier: OidcIdTokenVerifier,
) {
    fun fetchIdentity(
        code: String,
        expectedNonce: String? = null,
    ): OAuthIdentity {
        require(code.isNotBlank()) { "인가 코드가 필요합니다." }
        val form = LinkedMultiValueMap<String, String>()
        form.add("grant_type", "authorization_code")
        form.add("client_id", properties.clientId)
        form.add("code", code)
        if (properties.clientSecret.isNotBlank()) {
            form.add("client_secret", properties.clientSecret)
        }
        if (properties.redirectUri.isNotBlank()) {
            form.add("redirect_uri", properties.redirectUri)
        }
        val tokens =
            oauthRequest {
                restClient
                    .post()
                    .uri("/auth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse::class.java)
            }
        val idToken =
            tokens.idToken?.takeIf { it.isNotBlank() }
                ?: throw OAuthClientException(OAuthClientException.Reason.INVALID_RESPONSE)
        val jwt = idTokenVerifier.verify(idToken)
        if (expectedNonce != null && jwt.getStringClaim("nonce") != expectedNonce) {
            throw OAuthClientException(OAuthClientException.Reason.INVALID_AUTHORIZATION)
        }
        val subject =
            jwt.subject?.takeIf { it.isNotBlank() }
                ?: throw OAuthClientException(OAuthClientException.Reason.INVALID_RESPONSE)
        return OAuthIdentity(subject)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private class TokenResponse(
        @param:JsonProperty("id_token") val idToken: String? = null,
    )
}
