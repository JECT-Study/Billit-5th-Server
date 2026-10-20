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
            .addResourceHandler("/swagger-ui.html")
            .addResourceLocations("classpath:/api-docs/")
        registry
            .addResourceHandler("/api-docs/**")
            .addResourceLocations("classpath:/api-docs/")
        registry
            .addResourceHandler("/scalar/**")
            .addResourceLocations(
                "classpath:/api-docs/scalar/",
                "classpath:/META-INF/resources/webjars/scalar__api-reference/1.68.0/dist/browser/",
            )
    }
}
