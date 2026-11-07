package com.ject.module.auth.infra

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
@EnableConfigurationProperties(JwtProperties::class)
class JwtConfig {
    @Bean
    fun jwtAccessTokenCodec(
        properties: JwtProperties,
        clock: Clock,
    ): JwtAccessTokenCodec = JwtAccessTokenCodec(properties, clock)
}
