package com.ject.module.auth.infra.client

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.domain.SocialIdentity
import com.ject.module.auth.infra.SocialLoginProperties
import com.ject.module.auth.usecase.port.SocialIdTokenPort
import com.ject.module.member.domain.AuthProvider
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator
import com.nimbusds.jose.jwk.source.JWKSetSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.jwk.source.RateLimitReachedException
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource
import com.nimbusds.jose.proc.JWSKeySelector
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
 * 구글, 카카오와 애플 ID 토큰을 제공자 JWKS로 검증합니다.
 *
 * - JWKS는 제공자별로 10분간 캐시합니다. 캐시에 없는 키(`kid`)의 토큰이 오면 다시 가져오되, 30초에 두 번까지만 가져와 요청마다 제공자를 호출하지 않습니다.
 * - 제공자 장애로 JWKS를 가져오지 못하면 마지막으로 받은 키 목록을 [OUTAGE_TOLERANCE] 동안 계속 사용합니다.
 * - 서명, 형식과 클레임 오류는 인증 실패(401)입니다. 키를 받은 적이 없거나 마지막 조회가 실패한 상태에서 토큰의 키를 확인하지 못하면 제공자 장애(503)로 응답합니다.
 *   마지막 조회가 성공한 키 목록에 없는 키는 재조회 제한에 걸려도 유효하지 않은 토큰(401)으로 봅니다.
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
    private val appleDecoder =
        idTokenDecoder(
            jwkSetUri = properties.apple.jwkSetUri,
            issuers = APPLE_ISSUERS,
            audience = properties.apple.clientId,
            clock = clock,
        )

    override fun verify(
        provider: AuthProvider,
        idToken: String,
    ): SocialIdentity {
        val (decoder, displayNameClaim) =
            when (provider) {
                AuthProvider.GOOGLE -> googleDecoder to GOOGLE_DISPLAY_NAME_CLAIM

                AuthProvider.KAKAO -> kakaoDecoder to KAKAO_DISPLAY_NAME_CLAIM

                // 애플은 ID 토큰에 이름을 담지 않습니다. 이름은 최초 권한 부여 때 앱이 요청 본문으로 보냅니다.
                AuthProvider.APPLE -> appleDecoder to null
            }
        val jwt =
            try {
                decoder.decode(idToken)
            } catch (exception: BadJwtException) {
                throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED, exception)
            } catch (exception: JwtException) {
                // 서명 키를 확인하지 못한 경우입니다. 토큰 자체의 문제는 BadJwtException으로 구분됩니다.
                log.warn(exception) { "event=social_provider_unavailable provider=$provider" }
                throw BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE, exception)
            }
        return SocialIdentity(
            subject = checkNotNull(jwt.subject),
            displayName = displayNameClaim?.let { jwt.getClaimAsString(it) }?.takeIf { it.isNotBlank() },
        )
    }

    private fun idTokenDecoder(
        jwkSetUri: String,
        issuers: Set<String>,
        audience: String,
        clock: Clock,
    ): JwtDecoder {
        val fetchTracker =
            JwkSetFetchTracker(
                URLBasedJWKSetSource(
                    URI.create(jwkSetUri).toURL(),
                    DefaultResourceRetriever(
                        CONNECT_TIMEOUT.toMillis().toInt(),
                        READ_TIMEOUT.toMillis().toInt(),
                        JWK_SET_SIZE_LIMIT_BYTES,
                    ),
                ),
            )
        val jwkSource =
            JWKSourceBuilder
                .create(fetchTracker)
                .cache(JWK_SET_CACHE_TTL.toMillis(), JWK_SET_REFRESH_TIMEOUT.toMillis())
                .outageTolerant(OUTAGE_TOLERANCE.toMillis())
                .build()
        val jwtProcessor = DefaultJWTProcessor<SecurityContext>()
        jwtProcessor.jwsKeySelector = providerAwareKeySelector(JWSVerificationKeySelector(JWSAlgorithm.RS256, jwkSource), fetchTracker)
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

    /**
     * 토큰의 키를 찾지 못했을 때 제공자 장애와 유효하지 않은 토큰을 구분합니다.
     *
     * 빈 목록을 반환하면 토큰 검증이 401로 실패하고, [KeySourceException]을 던지면 제공자 장애(503)로 처리됩니다.
     * 재조회 제한은 실패한 조회도 세므로, 마지막 조회가 실패한 상태에서 제한에 걸리면 장애로 봅니다.
     */
    private fun providerAwareKeySelector(
        delegate: JWSVerificationKeySelector<SecurityContext>,
        fetchTracker: JwkSetFetchTracker,
    ): JWSKeySelector<SecurityContext> =
        JWSKeySelector { header, context ->
            val keys =
                try {
                    delegate.selectJWSKeys(header, context)
                } catch (exception: RateLimitReachedException) {
                    if (fetchTracker.lastFetchFailed) throw exception
                    emptyList()
                }
            if (keys.isEmpty() && header.algorithm == JWSAlgorithm.RS256 && fetchTracker.lastFetchFailed) {
                throw KeySourceException("The JWK set of the provider is unavailable")
            }
            keys
        }

    /** 제공자 JWKS 조회의 마지막 결과를 기록합니다. 조회한 적이 없으면 실패로 봅니다. */
    private class JwkSetFetchTracker(
        private val delegate: JWKSetSource<SecurityContext>,
    ) : JWKSetSource<SecurityContext> {
        @Volatile
        var lastFetchFailed: Boolean = true
            private set

        override fun getJWKSet(
            refreshEvaluator: JWKSetCacheRefreshEvaluator,
            currentTime: Long,
            context: SecurityContext?,
        ): JWKSet =
            try {
                delegate.getJWKSet(refreshEvaluator, currentTime, context).also { lastFetchFailed = false }
            } catch (exception: KeySourceException) {
                lastFetchFailed = true
                throw exception
            }

        override fun close() {
            delegate.close()
        }
    }

    companion object {
        private val log = KotlinLogging.logger {}

        private val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
        private val KAKAO_ISSUERS = setOf("https://kauth.kakao.com")
        private val APPLE_ISSUERS = setOf("https://appleid.apple.com")
        private const val GOOGLE_DISPLAY_NAME_CLAIM = "name"
        private const val KAKAO_DISPLAY_NAME_CLAIM = "nickname"

        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
        private const val JWK_SET_SIZE_LIMIT_BYTES = 50 * 1024
        private val JWK_SET_CACHE_TTL: Duration = Duration.ofMinutes(10)
        private val JWK_SET_REFRESH_TIMEOUT: Duration = Duration.ofSeconds(6)

        /** 제공자 장애 중 마지막으로 받은 키 목록을 계속 사용하는 시간입니다. */
        private val OUTAGE_TOLERANCE: Duration = Duration.ofHours(1)

        /** 제공자와 서버의 시계 차이를 허용하는 범위입니다. */
        private val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    }
}
