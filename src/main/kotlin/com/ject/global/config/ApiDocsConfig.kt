package com.ject.global.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
@ConditionalOnProperty(prefix = "app.api-docs", name = ["enabled"], havingValue = "true")
class ApiDocsConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry
            .addResourceHandler("/openapi.yml")
            .addResourceLocations("classpath:/api-docs/")
        registry
            .addResourceHandler("/swagger-ui/**")
            .addResourceLocations(
                "classpath:/api-docs/swagger-ui/",
                "classpath:/META-INF/resources/webjars/swagger-ui/5.32.2/",
            )
    }
}
