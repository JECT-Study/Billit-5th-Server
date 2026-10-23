package com.ject.module.auth.presentation

import com.ject.module.auth.infra.JwtAccessTokenCodec
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        authenticationEntryPoint: ApiAuthenticationEntryPoint,
        accessDeniedHandler: ApiAccessDeniedHandler,
        jwtAccessTokenCodec: JwtAccessTokenCodec,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling {
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }.authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                it.requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/api-docs/**", "/scalar/**").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/learning/stocks/{stockId}/reservations").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/learning/stocks/{stockId}/reservations").permitAll()
                it.anyRequest().authenticated()
            }
        http.addFilterBefore(
            JwtAuthenticationFilter(jwtAccessTokenCodec, authenticationEntryPoint),
            UsernamePasswordAuthenticationFilter::class.java,
        )
        return http.build()
    }
}
