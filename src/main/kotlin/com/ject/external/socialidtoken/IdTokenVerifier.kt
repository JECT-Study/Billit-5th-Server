package com.ject.external.socialidtoken

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.KeySourceException
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.CachingJWKSetSource
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator
import com.nimbusds.jose.jwk.source.JWKSetSource
import com.nimbusds.jose.jwk.source.JWKSetUnavailableException
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.OutageTolerantJWKSetSource
import com.nimbusds.jose.jwk.source.RateLimitReachedException
import com.nimbusds.jose.jwk.source.RateLimitedJWKSetSource
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource
import com.nimbusds.jose.proc.JWSKeySelector
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.net.URI
import java.time.Clock
import java.time.Duration

/**
 * 제공자 하나(구글, 카카오, 애플 등)의 ID 토큰을 그 제공자의 JWKS로 검증합니다.
 *
 * - JWKS는 10분간 캐시합니다. 캐시 만료와 캐시에 없는 키(`kid`)의 토큰 때문에 다시 가져오는 요청은 합쳐서 30초에 두 번까지만 제공자에 보냅니다.
 * - 제공자 장애나 재조회 제한으로 JWKS를 가져오지 못하면 마지막으로 받은 키 목록을 [OUTAGE_TOLERANCE] 동안 계속 사용합니다.
 *   그래서 모르는 `kid`의 토큰이 재조회 한도를 채워도, 마지막 키 목록에 있는 키의 토큰은 캐시가 만료된 순간에도 그 목록으로 검증됩니다.
 * - 서명, 형식과 클레임 오류는 [IdTokenFailureReason.INVALID_TOKEN]입니다. 키를 받은 적이 없거나 마지막 조회가 실패한 상태에서 토큰의 키를 확인하지 못하면
 *   [IdTokenFailureReason.KEY_SOURCE_UNAVAILABLE]입니다. 마지막 조회가 성공한 키 목록에 없는 키는 재조회 제한에 걸려도 유효하지 않은 토큰으로 봅니다.
 * - 캐시 만료, 재조회 제한과 `exp` 판단은 주입받은 [Clock]으로 합니다. JWKS 조회는 연결 2초, 응답 3초로 제한합니다.
 */
class IdTokenVerifier(
    jwkSetUri: String,
    issuers: Set<String>,
    audience: String,
    clock: Clock,
) {
    private val decoder = idTokenDecoder(jwkSetUri, issuers, audience, clock)

    /** 검증에 실패하면 [IdTokenVerificationException]을 던집니다. */
    fun verify(idToken: String): VerifiedIdToken {
        val jwt =
            try {
                decoder.decode(idToken)
            } catch (exception: BadJwtException) {
                throw IdTokenVerificationException(IdTokenFailureReason.INVALID_TOKEN, exception)
            } catch (exception: JwtException) {
                // 서명 키를 확인하지 못한 경우입니다. 토큰 자체의 문제는 BadJwtException으로 구분됩니다.
                throw IdTokenVerificationException(IdTokenFailureReason.KEY_SOURCE_UNAVAILABLE, exception)
            }
        return VerifiedIdToken(subject = checkNotNull(jwt.subject), claims = jwt.claims)
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
        // JWKSourceBuilder는 재조회 제한을 장애 내성 층보다 위에 두어, 제한에 걸리면 마지막 키 목록을 쓰지 못합니다.
        // 제한을 장애 내성 층 아래에 두고 제한을 장애처럼 처리해 마지막 키 목록으로 검증하게 합니다.
        val jwkSetSource =
            CachingJWKSetSource(
                OutageTolerantJWKSetSource(
                    RateLimitAsUnavailable(RateLimitedJWKSetSource(fetchTracker, JWK_SET_REFETCH_INTERVAL.toMillis(), null)),
                    OUTAGE_TOLERANCE.toMillis(),
                    null,
                ),
                JWK_SET_CACHE_TTL.toMillis(),
                JWK_SET_REFRESH_TIMEOUT.toMillis(),
                null,
            )
        // JWKSetBasedJWKSource와 같은 순서로 찾되, 시각을 시스템 시계 대신 주입받은 Clock에서 구합니다.
        val jwkSource =
            JWKSource<SecurityContext> { selector, context ->
                val now = clock.millis()
                val jwkSet = jwkSetSource.getJWKSet(JWKSetCacheRefreshEvaluator.noRefresh(), now, context)
                selector.select(jwkSet).ifEmpty {
                    selector.select(jwkSetSource.getJWKSet(JWKSetCacheRefreshEvaluator.referenceComparison(jwkSet), now, context))
                }
            }
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
     * 재조회 제한은 실패한 조회도 세므로, 마지막 조회가 실패한 상태에서 키를 찾지 못하면 제한 여부와 관계없이 장애로 봅니다.
     */
    private fun providerAwareKeySelector(
        delegate: JWSVerificationKeySelector<SecurityContext>,
        fetchTracker: JwkSetFetchTracker,
    ): JWSKeySelector<SecurityContext> =
        JWSKeySelector { header, context ->
            val keys = delegate.selectJWSKeys(header, context)
            if (keys.isEmpty() && header.algorithm == JWSAlgorithm.RS256 && fetchTracker.lastFetchFailed) {
                throw KeySourceException("The JWK set of the provider is unavailable")
            }
            keys
        }

    /** 재조회 제한에 걸리면 제공자 장애와 같은 예외로 바꿔, 바깥의 장애 내성 층이 마지막으로 받은 키 목록을 돌려주게 합니다. */
    private class RateLimitAsUnavailable(
        private val delegate: JWKSetSource<SecurityContext>,
    ) : JWKSetSource<SecurityContext> {
        override fun getJWKSet(
            refreshEvaluator: JWKSetCacheRefreshEvaluator,
            currentTime: Long,
            context: SecurityContext?,
        ): JWKSet =
            try {
                delegate.getJWKSet(refreshEvaluator, currentTime, context)
            } catch (exception: RateLimitReachedException) {
                throw JWKSetUnavailableException("The JWK set refetch rate limit was reached", exception)
            }

        override fun close() {
            delegate.close()
        }
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
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(3)
        private const val JWK_SET_SIZE_LIMIT_BYTES = 50 * 1024
        private val JWK_SET_CACHE_TTL: Duration = Duration.ofMinutes(10)
        private val JWK_SET_REFRESH_TIMEOUT: Duration = Duration.ofSeconds(6)

        /** 제공자에 JWKS를 다시 요청하는 간격입니다. 이 간격마다 두 번까지 요청합니다. */
        private val JWK_SET_REFETCH_INTERVAL: Duration = Duration.ofSeconds(30)

        /** 제공자 장애 중 마지막으로 받은 키 목록을 계속 사용하는 시간입니다. */
        private val OUTAGE_TOLERANCE: Duration = Duration.ofHours(1)

        /** 제공자와 서버의 시계 차이를 허용하는 범위입니다. */
        private val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    }
}
