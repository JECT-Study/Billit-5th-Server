package com.ject.module.trip.infra

import com.ject.module.trip.implement.TripInvitationUrlCreator
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(TripInvitationProperties::class)
class TripConfig {
    @Bean
    fun tripInvitationUrlCreator(properties: TripInvitationProperties): TripInvitationUrlCreator =
        TripInvitationUrlCreator(properties.baseUrl)
}
