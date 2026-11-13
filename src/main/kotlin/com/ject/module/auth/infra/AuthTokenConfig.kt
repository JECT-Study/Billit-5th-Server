package com.ject.module.auth.infra

import com.ject.module.auth.domain.AuthTokenClaims
import com.ject.module.auth.domain.TokenType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration

@Configuration
@EnableConfigurationProperties(AuthTokenProperties::class)
class AuthTokenConfig {
    @Bean
    fun jwtEncoder(properties: AuthTokenProperties): JwtEncoder =
        NimbusJwtEncoder
            .withSecretKey(properties.signingKey)
            .algorithm(MacAlgorithm.HS512)
            .build()

    /** 회원용 Access Token만 허용합니다. */
    @Bean
    fun memberJwtDecoder(
        properties: AuthTokenProperties,
        clock: Clock,
        accessTokenSessionValidator: AccessTokenSessionValidator,
    ): JwtDecoder = jwtDecoder(properties, clock, accessTokenSessionValidator, setOf(TokenType.ACCESS))

    /** 가입 API에서 사용합니다. 회원용 Access Token은 인증한 뒤 권한 부족으로 거절하도록 함께 허용합니다. */
    @Bean
    fun registrationJwtDecoder(
        properties: AuthTokenProperties,
        clock: Clock,
        accessTokenSessionValidator: AccessTokenSessionValidator,
    ): JwtDecoder = jwtDecoder(properties, clock, accessTokenSessionValidator, setOf(TokenType.ACCESS, TokenType.REGISTRATION))

    private fun jwtDecoder(
        properties: AuthTokenProperties,
        clock: Clock,
        accessTokenSessionValidator: AccessTokenSessionValidator,
        allowedTokenTypes: Set<TokenType>,
    ): JwtDecoder {
        val decoder =
            NimbusJwtDecoder
                .withSecretKey(properties.signingKey)
                .macAlgorithm(MacAlgorithm.HS512)
                .build()
        // 같은 서버가 발급하고 검증하므로 만료 시각에 허용 오차를 두지 않고, 만료 시각이 없는 토큰은 거절합니다.
        val timestampValidator = JwtTimestampValidator(Duration.ZERO)
        timestampValidator.setClock(clock)
        timestampValidator.setAllowEmptyExpiryClaim(false)
        val allowedTokenTypeNames = allowedTokenTypes.map { it.name }.toSet()
        val tokenTypeValidator =
            OAuth2TokenValidator<Jwt> { jwt ->
                if (jwt.getClaimAsString(AuthTokenClaims.TOKEN_TYPE) in allowedTokenTypeNames) {
                    OAuth2TokenValidatorResult.success()
                } else {
                    OAuth2TokenValidatorResult.failure(
                        OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The token type is not allowed", null),
                    )
                }
            }
        val claimsValidator = DelegatingOAuth2TokenValidator(timestampValidator, tokenTypeValidator)
        // 서명은 검증기보다 먼저 확인합니다. DelegatingOAuth2TokenValidator는 앞 검증이 실패해도 나머지를 모두 실행하므로,
        // 만료 시각과 토큰 종류까지 통과한 토큰만 세션을 조회합니다.
        decoder.setJwtValidator { jwt ->
            val claimsResult = claimsValidator.validate(jwt)
            if (claimsResult.hasErrors()) claimsResult else accessTokenSessionValidator.validate(jwt)
        }
        return decoder
    }
}
