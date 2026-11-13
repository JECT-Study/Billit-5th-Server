package com.ject.module.auth.infra

import com.ject.module.auth.domain.AuthTokenClaims
import com.ject.module.auth.domain.TokenType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.ObjectPostProcessor
import org.springframework.security.config.annotation.web.HttpSecurityDsl
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher

@Configuration
class SecurityConfig {
    /** 가입 API는 가입 전용 토큰만 허용합니다. 회원용 Access Token은 인증한 뒤 403으로 거절합니다. */
    @Bean
    @Order(1)
    fun registrationSecurityFilterChain(
        http: HttpSecurity,
        @Qualifier("registrationJwtDecoder") registrationJwtDecoder: JwtDecoder,
        securityErrorResponseWriter: SecurityErrorResponseWriter,
    ): SecurityFilterChain {
        http {
            securityMatcher(*REGISTRATION_PATHS)
            applyStatelessApiDefaults(securityErrorResponseWriter)
            authorizeHttpRequests {
                authorize(anyRequest, hasAuthority(TokenType.REGISTRATION.name))
            }
            oauth2ResourceServer {
                authenticationEntryPoint = securityErrorResponseWriter
                accessDeniedHandler = securityErrorResponseWriter
                jwt {
                    jwtDecoder = registrationJwtDecoder
                    jwtAuthenticationConverter = tokenTypeAuthenticationConverter()
                }
            }
        }
        http.respondToAuthenticationServiceFailure(securityErrorResponseWriter)
        return http.build()
    }

    /** 공개 경로를 제외한 요청은 회원용 Access Token이 필요합니다. 가입 전용 토큰은 인증 실패로 처리합니다. */
    @Bean
    @Order(2)
    fun apiSecurityFilterChain(
        http: HttpSecurity,
        @Qualifier("memberJwtDecoder") memberJwtDecoder: JwtDecoder,
        securityErrorResponseWriter: SecurityErrorResponseWriter,
    ): SecurityFilterChain {
        http {
            applyStatelessApiDefaults(securityErrorResponseWriter)
            authorizeHttpRequests {
                // 관리 포트의 actuator는 서버 밖으로 열지 않으므로 기존처럼 인증 없이 응답합니다.
                authorize(EndpointRequest.toAnyEndpoint(), permitAll)
                for (path in PUBLIC_PATHS) {
                    authorize(path, permitAll)
                }
                // 명세상 Access Token 없이도 호출할 수 있습니다. 헤더로 보낸 토큰은 검증합니다.
                authorize(LOGOUT_PATH, permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                bearerTokenResolver = publicPathIgnoringBearerTokenResolver()
                authenticationEntryPoint = securityErrorResponseWriter
                accessDeniedHandler = securityErrorResponseWriter
                jwt {
                    jwtDecoder = memberJwtDecoder
                    jwtAuthenticationConverter = tokenTypeAuthenticationConverter()
                }
            }
        }
        http.respondToAuthenticationServiceFailure(securityErrorResponseWriter)
        return http.build()
    }

    private fun HttpSecurityDsl.applyStatelessApiDefaults(securityErrorResponseWriter: SecurityErrorResponseWriter) {
        csrf { disable() }
        httpBasic { disable() }
        formLogin { disable() }
        logout { disable() }
        sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
        exceptionHandling {
            authenticationEntryPoint = securityErrorResponseWriter
            accessDeniedHandler = securityErrorResponseWriter
        }
    }

    /**
     * 세션 조회 실패처럼 인증을 판단하지 못한 장애도 [SecurityErrorResponseWriter]가 공통 오류 형식으로 응답하게 합니다.
     *
     * 토큰 인증 필터의 기본 실패 처리기는 이 장애를 다시 던지므로, 그대로 두면 공통 오류 형식이 아닌 500으로 끝납니다.
     */
    private fun HttpSecurity.respondToAuthenticationServiceFailure(securityErrorResponseWriter: SecurityErrorResponseWriter) {
        val failureHandler = AuthenticationEntryPointFailureHandler(securityErrorResponseWriter)
        failureHandler.setRethrowAuthenticationServiceException(false)
        oauth2ResourceServer { configurer ->
            configurer.withObjectPostProcessor(
                object : ObjectPostProcessor<BearerTokenAuthenticationFilter> {
                    override fun <O : BearerTokenAuthenticationFilter> postProcess(filter: O): O {
                        filter.setAuthenticationFailureHandler(failureHandler)
                        return filter
                    }
                },
            )
        }
    }

    private fun tokenTypeAuthenticationConverter(): JwtAuthenticationConverter {
        val authoritiesConverter = JwtGrantedAuthoritiesConverter()
        authoritiesConverter.setAuthoritiesClaimName(AuthTokenClaims.TOKEN_TYPE)
        authoritiesConverter.setAuthorityPrefix("")
        val authenticationConverter = JwtAuthenticationConverter()
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter)
        return authenticationConverter
    }

    /** 토큰이 필요 없는 경로에서는 남아 있는 Authorization 헤더 때문에 만료 토큰으로 거절되지 않도록 토큰을 읽지 않습니다. */
    private fun publicPathIgnoringBearerTokenResolver(): BearerTokenResolver {
        val defaultResolver = DefaultBearerTokenResolver()
        val publicPaths = OrRequestMatcher(PUBLIC_PATHS.map { PathPatternRequestMatcher.withDefaults().matcher(it) })
        return BearerTokenResolver { request ->
            if (publicPaths.matches(request)) null else defaultResolver.resolve(request)
        }
    }

    companion object {
        private val REGISTRATION_PATHS =
            arrayOf(
                "/api/v1/me/registration/**",
                "/api/v1/nickname-availability",
            )

        private val PUBLIC_PATHS =
            listOf(
                "/healthz",
                "/readyz",
                "/swagger-ui.html",
                "/api-docs/**",
                "/scalar/**",
                "/api/v1/auth/social-logins",
                "/api/v1/auth/token-refreshes",
                "/error",
            )

        private const val LOGOUT_PATH = "/api/v1/auth/logout"
    }
}
