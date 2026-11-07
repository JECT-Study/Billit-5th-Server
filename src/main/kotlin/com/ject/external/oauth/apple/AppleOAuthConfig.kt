package com.ject.external.oauth.apple

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
@ConditionalOnProperty(prefix = "app.oauth.apple", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(AppleOAuthProperties::class)
class AppleOAuthConfig {
    @Bean
    fun appleOAuthClient(properties: AppleOAuthProperties): AppleOAuthClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(Duration.ofSeconds(5))
        val restClient =
            RestClient
                .builder()
                .baseUrl("https://appleid.apple.com")
                .requestFactory(requestFactory)
                .build()
        val keySource =
            JWKSourceBuilder
                .create<SecurityContext>(
                    URI("https://appleid.apple.com/auth/keys").toURL(),
                    DefaultResourceRetriever(2_000, 5_000, 100_000),
                ).retrying(false)
                .build()
        val verifier = OidcIdTokenVerifier(properties.clientId, setOf("https://appleid.apple.com"), keySource)
        return AppleOAuthClient(restClient, properties, verifier)
    }
}
