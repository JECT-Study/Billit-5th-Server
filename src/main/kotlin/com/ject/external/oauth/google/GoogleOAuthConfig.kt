package com.ject.external.oauth.google

import com.ject.external.oauth.OidcIdTokenVerifier
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

@Configuration
@ConditionalOnProperty(prefix = "app.oauth.google", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(GoogleOAuthProperties::class)
class GoogleOAuthConfig {
    @Bean
    fun googleOAuthClient(properties: GoogleOAuthProperties): GoogleOAuthClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(Duration.ofSeconds(5))
        val restClient =
            RestClient
                .builder()
                .baseUrl("https://oauth2.googleapis.com")
                .requestFactory(requestFactory)
                .build()
        val keySource =
            JWKSourceBuilder
                .create<SecurityContext>(
                    URI("https://www.googleapis.com/oauth2/v3/certs").toURL(),
                    DefaultResourceRetriever(2_000, 5_000, 100_000),
                ).retrying(false)
                .build()
        val verifier = OidcIdTokenVerifier(properties.clientId, setOf("accounts.google.com", "https://accounts.google.com"), keySource)
        return GoogleOAuthClient(restClient, properties, verifier)
    }
}
