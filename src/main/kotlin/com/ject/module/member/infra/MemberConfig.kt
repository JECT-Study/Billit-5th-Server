package com.ject.module.member.infra

import com.ject.module.member.implement.PolicyContentUrlCreator
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(PolicyProperties::class)
class MemberConfig {
    @Bean
    fun policyContentUrlCreator(properties: PolicyProperties): PolicyContentUrlCreator = PolicyContentUrlCreator(properties.contentBaseUrl)
}
