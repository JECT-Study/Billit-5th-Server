package com.ject.module.exchangerate.infra.client

import com.ject.external.exchangerate.ExchangeRateClient
import com.ject.external.exchangerate.ExchangeRateException
import com.ject.external.exchangerate.ExchangeRateFailureReason
import com.ject.external.exchangerate.frankfurter.FrankfurterProperties
import com.ject.global.error.BusinessException
import com.ject.module.exchangerate.domain.ExchangeRateErrorCode
import com.ject.module.exchangerate.domain.PublishedExchangeRate
import com.ject.module.exchangerate.usecase.port.ReferenceRateProviderPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Frankfurter로 결제 통화를 기준 통화, `KRW`를 대상 통화로 조회합니다.
 *
 * Frankfurter는 휴일의 날짜를 요청하면 그 이전 가장 최근 고시일의 값을, 고시값이 없는 날짜(시리즈 시작 이전)에는 빈 목록을 `200`으로 줍니다.
 * 빈 목록은 고시값 없음(`null`)이고, 장애와 해석할 수 없는 응답은 모두 조회 불가입니다. 응답 본문은 로그에 남기지 않습니다.
 */
@Component
class FrankfurterReferenceRateAdapter(
    private val exchangeRateClient: ExchangeRateClient,
    properties: FrankfurterProperties,
) : ReferenceRateProviderPort {
    override val provider: String = properties.source

    override fun fetch(
        currency: String,
        date: LocalDate,
    ): PublishedExchangeRate? {
        val rates =
            try {
                exchangeRateClient.fetch(currency, setOf(KRW), date)
            } catch (exception: ExchangeRateException) {
                logUnavailable(exception.reason, exception.statusCode)
                throw BusinessException(ExchangeRateErrorCode.PROVIDER_UNAVAILABLE, exception)
            }

        if (rates.isEmpty()) {
            return null
        }
        if (rates.size > 1) {
            logUnavailable(ExchangeRateFailureReason.INVALID_RESPONSE, null)
            throw BusinessException(ExchangeRateErrorCode.PROVIDER_UNAVAILABLE)
        }
        val rate = rates.single()
        return PublishedExchangeRate(baseDate = rate.date, rate = rate.rate)
    }

    private fun logUnavailable(
        reason: ExchangeRateFailureReason,
        status: Int?,
    ) {
        log.warn { "event=exchange_rate_unavailable provider=$provider reason=$reason status=$status" }
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private const val KRW = "KRW"
    }
}
