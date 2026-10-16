package com.ject.external.exchangerate.frankfurter

import com.ject.external.exchangerate.ExchangeRateClient
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

@Configuration
@EnableConfigurationProperties(FrankfurterProperties::class)
class FrankfurterConfig {
    @Bean
    fun exchangeRateClient(
        properties: FrankfurterProperties,
        clock: Clock,
    ): ExchangeRateClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        requestFactory.setReadTimeout(Duration.ofSeconds(5))
        val restClient =
            RestClient
                .builder()
                .baseUrl("https://api.frankfurter.dev")
                .requestFactory(requestFactory)
                .build()
        return FrankfurterExchangeRateClient(restClient, properties.source, clock)
    }
}
