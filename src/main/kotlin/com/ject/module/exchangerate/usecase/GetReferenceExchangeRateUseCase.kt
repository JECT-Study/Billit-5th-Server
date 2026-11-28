package com.ject.module.exchangerate.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.exchangerate.domain.ExchangeRateErrorCode
import com.ject.module.exchangerate.domain.ReferenceExchangeRateRepository
import com.ject.module.exchangerate.domain.ReferenceExchangeRateResult
import com.ject.module.exchangerate.usecase.port.ReferenceRateProviderPort
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * 결제일의 기준 환율을 조회합니다. 저장한 환율이 있으면 외부를 호출하지 않습니다.
 *
 * 고시 기관을 호출하는 동안 DB 트랜잭션을 열어 두지 않도록 트랜잭션 없이 실행합니다. 읽기와 저장은 각각 Repository 호출 하나로 끝납니다.
 */
@Service
class GetReferenceExchangeRateUseCase(
    private val referenceExchangeRateRepository: ReferenceExchangeRateRepository,
    private val referenceRateProviderPort: ReferenceRateProviderPort,
    private val clock: Clock,
) {
    /**
     * [currency]는 `KRW`가 아닌 외화 코드입니다. 입력 형식은 호출하는 쪽에서 확인합니다.
     *
     * 1. [paidDate]가 서버 날짜(`Asia/Seoul`)보다 미래이면 400 `COMMON_INVALID_REQUEST`입니다.
     * 2. 저장한 행이 있으면 그 값을 반환합니다.
     * 3. 고시 기관의 값이 없거나 기준일이 [paidDate]보다 7일 넘게 이전이면 404 `EXCHANGE_RATE_NOT_PUBLISHED`이며 저장하지 않습니다.
     * 4. 기준일이 [paidDate]와 같거나 [paidDate]가 서버 날짜보다 2일 이상 이전이면 저장하고, 아니면 응답에만 씁니다.
     *    최근 날짜의 이전 고시값을 저장하면 나중에 그 날짜의 고시값이 나와도 저장한 행이 바뀌지 않기 때문입니다.
     * 5. 고시 기관의 장애는 503 `EXCHANGE_RATE_PROVIDER_UNAVAILABLE`이며 저장하지 않습니다.
     *
     * 저장하는 값은 반올림하지 않은 원본이고, 응답의 환율만 소수점 셋째 자리에서 `HALF_UP`으로 반올림합니다.
     */
    fun get(
        currency: String,
        paidDate: LocalDate,
    ): ReferenceExchangeRateResult {
        val now = clock.instant()
        val today = now.atZone(SEOUL).toLocalDate()
        if (paidDate.isAfter(today)) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }

        val provider = referenceRateProviderPort.provider
        val stored = referenceExchangeRateRepository.find(provider, currency, paidDate)
        if (stored != null) {
            return result(currency, stored.rate, provider, stored.baseDate)
        }

        val published = referenceRateProviderPort.fetch(currency, paidDate)
        if (published == null || published.baseDate.isBefore(paidDate.minusDays(MAX_FALLBACK_DAYS))) {
            throw BusinessException(ExchangeRateErrorCode.NOT_PUBLISHED)
        }

        if (published.baseDate == paidDate || !paidDate.isAfter(today.minusDays(MIN_DAYS_BEFORE_STORING_FALLBACK))) {
            referenceExchangeRateRepository.saveIfAbsent(provider, currency, paidDate, published.baseDate, published.rate, now)
        }
        return result(currency, published.rate, provider, published.baseDate)
    }

    private fun result(
        currency: String,
        rate: BigDecimal,
        provider: String,
        baseDate: LocalDate,
    ) = ReferenceExchangeRateResult(currency, rate.setScale(2, RoundingMode.HALF_UP), provider, baseDate)

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private const val MAX_FALLBACK_DAYS = 7L
        private const val MIN_DAYS_BEFORE_STORING_FALLBACK = 2L
    }
}
