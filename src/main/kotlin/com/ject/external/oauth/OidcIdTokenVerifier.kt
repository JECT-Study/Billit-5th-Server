package com.ject.external.oauth

import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.BadJOSEException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.BadJWTException
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.text.ParseException

class OidcIdTokenVerifier(
    clientId: String,
    issuers: Set<String>,
    keySource: JWKSource<SecurityContext>,
) {
    private val processor =
        DefaultJWTProcessor<SecurityContext>().apply {
            jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, keySource)
            val claimsVerifier =
                DefaultJWTClaimsVerifier<SecurityContext>(
                    setOf(clientId),
                    JWTClaimsSet.Builder().build(),
                    setOf("iss", "sub", "aud", "exp", "iat"),
                    emptySet(),
                )
            jwtClaimsSetVerifier =
                com.nimbusds.jwt.proc.JWTClaimsSetVerifier { claims, context ->
                    claimsVerifier.verify(claims, context)
                    if (claims.issuer !in issuers || claims.subject.isNullOrBlank()) {
                        throw BadJWTException("Invalid identity claims")
                    }
                    claims.getStringClaim("nonce")
                    val authorizedParty = claims.getStringClaim("azp")
                    if (authorizedParty != null && authorizedParty != clientId) {
                        throw BadJWTException("Invalid authorized party")
                    }
                }
        }

    fun verify(token: String): JWTClaimsSet =
        try {
            processor.process(token, null)
        } catch (exception: ParseException) {
            throw OAuthClientException(OAuthClientException.Reason.INVALID_RESPONSE)
        } catch (exception: BadJOSEException) {
            throw OAuthClientException(OAuthClientException.Reason.INVALID_RESPONSE)
        } catch (exception: JOSEException) {
            throw OAuthClientException(OAuthClientException.Reason.UNAVAILABLE)
        }
}
