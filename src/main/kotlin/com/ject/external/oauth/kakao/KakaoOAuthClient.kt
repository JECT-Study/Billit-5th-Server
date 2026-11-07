package com.ject.external.oauth.kakao

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.ject.external.oauth.OAuthClientException
import com.ject.external.oauth.OAuthIdentity
import com.ject.external.oauth.oauthRequest
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient

class KakaoOAuthClient(
    private val restClient: RestClient,
    private val properties: KakaoOAuthProperties,
    private val userRestClient: RestClient,
) {
    fun fetchIdentity(code: String): OAuthIdentity {
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
                    .uri("/oauth/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse::class.java)
            }
        val accessToken =
            tokens.accessToken?.takeIf { it.isNotBlank() }
                ?: throw OAuthClientException(OAuthClientException.Reason.INVALID_RESPONSE)
        val user =
            oauthRequest {
                userRestClient
                    .get()
                    .uri("/v2/user/me")
                    .headers { it.setBearerAuth(accessToken) }
                    .retrieve()
                    .body(UserResponse::class.java)
            }
        val id =
            user.id?.takeIf { it > 0 }
                ?: throw OAuthClientException(OAuthClientException.Reason.INVALID_RESPONSE)
        return OAuthIdentity(id.toString())
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private class TokenResponse(
        @param:JsonProperty("access_token") val accessToken: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private class UserResponse(
        val id: Long? = null,
    )
}
