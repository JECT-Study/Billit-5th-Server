package com.ject.external.oauth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

internal fun <T : Any> oauthRequest(block: () -> T?): T =
    try {
        block() ?: throw OAuthClientException(OAuthClientException.Reason.INVALID_RESPONSE)
    } catch (exception: RestClientResponseException) {
        val error =
            try {
                exception.getResponseBodyAs(OAuthErrorResponse::class.java)?.error
            } catch (conversionFailure: RestClientException) {
                null
            }
        val reason =
            when {
                exception.statusCode.value() == 429 || exception.statusCode.is5xxServerError -> OAuthClientException.Reason.UNAVAILABLE
                error == "invalid_client" || error == "unauthorized_client" -> OAuthClientException.Reason.CONFIGURATION
                error == "invalid_grant" || error == "access_denied" -> OAuthClientException.Reason.INVALID_AUTHORIZATION
                else -> OAuthClientException.Reason.INVALID_RESPONSE
            }
        throw OAuthClientException(reason, exception.statusCode.value())
    } catch (exception: ResourceAccessException) {
        throw OAuthClientException(OAuthClientException.Reason.UNAVAILABLE)
    } catch (exception: RestClientException) {
        throw OAuthClientException(OAuthClientException.Reason.INVALID_RESPONSE)
    }

@JsonIgnoreProperties(ignoreUnknown = true)
private class OAuthErrorResponse(
    val error: String? = null,
)
