package com.ject.external.oauth.kakao

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Configuration
@ConditionalOnProperty(prefix = "app.oauth.kakao", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(KakaoOAuthProperties::class)
class KakaoOAuthConfig {
    @Bean
    fun kakaoOAuthClient(properties: KakaoOAuthProperties): KakaoOAuthClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(Duration.ofSeconds(5))
        val restClient =
            RestClient
                .builder()
                .baseUrl("https://kauth.kakao.com")
                .requestFactory(requestFactory)
                .build()
        return KakaoOAuthClient(
            restClient,
            properties,
            RestClient
                .builder()
                .baseUrl("https://kapi.kakao.com")
                .requestFactory(requestFactory)
                .build(),
        )
    }
}
