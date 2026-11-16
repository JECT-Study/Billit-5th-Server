package com.ject.module.auth.infra.client

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.infra.SocialLoginProperties
import com.ject.module.auth.usecase.port.SocialIdTokenPort
import com.ject.module.member.domain.AuthProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.jwk.source.RateLimitReachedException
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.time.Duration

/**
 * 구글과 카카오 ID 토큰을 제공자 JWKS로 검증합니다.
 *
 * JWKS는 제공자별로 캐시하고 만료 후 다시 가져옵니다. 캐시에 없는 키(`kid`)의 토큰이 오면 JWKS를 다시 가져오되, 30초에 두 번까지만 가져와 요청마다 제공자를 호출하지 않습니다.
 * 서명, 형식과 클레임 오류는 인증 실패(401), JWKS 조회 실패와 타임아웃은 제공자 장애(503)로 구분합니다.
 */
@Component
class SocialIdTokenAdapter(
    properties: SocialLoginProperties,
    clock: Clock,
) : SocialIdTokenPort {
    private val googleDecoder =
        idTokenDecoder(
            jwkSetUri = properties.google.jwkSetUri,
            issuers = GOOGLE_ISSUERS,
            audience = properties.google.clientId,
            clock = clock,
        )
    private val kakaoDecoder =
        idTokenDecoder(
            jwkSetUri = properties.kakao.jwkSetUri,
            issuers = KAKAO_ISSUERS,
            audience = properties.kakao.nativeAppKey,
            clock = clock,
        )

    override fun verify(
        provider: AuthProvider,
        idToken: String,
    ): String {
        val decoder =
            when (provider) {
                AuthProvider.GOOGLE -> googleDecoder
                AuthProvider.KAKAO -> kakaoDecoder
                AuthProvider.APPLE -> throw IllegalArgumentException("Apple 로그인은 ID 토큰만으로 처리하지 않습니다.")
            }
        val jwt =
            try {
                decoder.decode(idToken)
            } catch (exception: BadJwtException) {
                throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED, exception)
            } catch (exception: JwtException) {
                // 최근에 가져온 키 목록에 없는 키라서 재조회를 제한한 경우는 유효하지 않은 토큰으로 봅니다.
                if (generateSequence(exception.cause) { it.cause }.any { it is RateLimitReachedException }) {
                    throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED, exception)
                }
                // 서명 키를 가져오지 못한 경우입니다. 토큰 자체의 문제는 BadJwtException으로 구분됩니다.
                log.warn(exception) { "event=social_provider_unavailable provider=$provider" }
                throw BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE, exception)
            }
        return checkNotNull(jwt.subject)
    }

    private fun idTokenDecoder(
        jwkSetUri: String,
        issuers: Set<String>,
        audience: String,
        clock: Clock,
    ): JwtDecoder {
        val jwkSource =
            JWKSourceBuilder
                .create<SecurityContext>(
                    URI.create(jwkSetUri).toURL(),
                    DefaultResourceRetriever(
                        CONNECT_TIMEOUT.toMillis().toInt(),
                        READ_TIMEOUT.toMillis().toInt(),
                        JWK_SET_SIZE_LIMIT_BYTES,
                    ),
                ).cache(JWK_SET_CACHE_TTL.toMillis(), JWK_SET_REFRESH_TIMEOUT.toMillis())
                .build()
        val jwtProcessor = DefaultJWTProcessor<SecurityContext>()
        jwtProcessor.jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource)
        // 클레임은 아래 Spring Security 검증기가 주입받은 Clock으로 확인합니다.
        jwtProcessor.setJWTClaimsSetVerifier { _, _ -> }
        val decoder = NimbusJwtDecoder(jwtProcessor)

        val timestampValidator = JwtTimestampValidator(CLOCK_SKEW)
        timestampValidator.setClock(clock)
        timestampValidator.setAllowEmptyExpiryClaim(false)
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                timestampValidator,
                JwtClaimValidator<Any>(JwtClaimNames.ISS) { it.toString() in issuers },
                JwtAudienceValidator(audience),
                JwtClaimValidator<String>(JwtClaimNames.SUB) { it.isNotBlank() },
            ),
        )
        return decoder
    }

    companion object {
        private val log = KotlinLogging.logger {}

        private val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
        private val KAKAO_ISSUERS = setOf("https://kauth.kakao.com")

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
        private const val JWK_SET_SIZE_LIMIT_BYTES = 50 * 1024
        private val JWK_SET_CACHE_TTL: Duration = Duration.ofMinutes(10)
        private val JWK_SET_REFRESH_TIMEOUT: Duration = Duration.ofSeconds(6)

        /** 제공자와 서버의 시계 차이를 허용하는 범위입니다. */
        private val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    }
}
