package com.ject.module.exchangerate.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.exchangerate.domain.ExchangeCurrency
import com.ject.module.exchangerate.domain.ExchangeMethod
import com.ject.module.exchangerate.domain.ManualExchangeRate
import com.ject.module.exchangerate.domain.ManualExchangeRateRepository
import com.ject.module.exchangerate.domain.ManualExchangeRateResult
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 여행에 참여 중인 회원이 본인의 환전 환율을 기록합니다.
 *
 * 같은 기록의 중복 등록은 사전 조회가 아니라 `uq_manual_exchange_rate_record` 제약으로 막으므로, 이 UseCase는 트랜잭션을 열지 않고 저장 한 번으로 끝냅니다.
 * 여행과 참여자는 여행 업무의 Repository에서 값으로 받습니다.
 */
@Service
class RegisterManualExchangeRateUseCase(
    private val tripRepository: TripRepository,
    private val manualExchangeRateRepository: ManualExchangeRateRepository,
) {
    /**
     * 1. [rate]가 0 이하이면 400입니다. 소수점 자릿수와 정수부 자릿수는 요청 형식 검증이 맡습니다.
     * 2. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행에 참여 중(`MEMBER`)이 아니면 403입니다. 나간 회원도 403입니다.
     * 3. 요청한 회원의 여행 참여자를 등록자로 저장합니다. 같은 여행에서 같은 등록자가 통화, 환전 날짜, 환전 수단과 환율이 모두 같은 기록을 이미 등록했으면 409 `EXCHANGE_RATE_DUPLICATED`입니다.
     */
    fun register(
        memberId: Long,
        tripId: Long,
        currency: ExchangeCurrency,
        rate: BigDecimal,
        exchangeMethod: ExchangeMethod,
        exchangeDate: LocalDate,
    ): ManualExchangeRateResult {
        if (rate.signum() <= 0) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }
        tripRepository.findTripStatus(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        val recorder =
            tripRepository.findParticipants(tripId).firstOrNull { it.memberId == memberId && it.status == TripMemberStatus.MEMBER }
                ?: throw BusinessException(CommonErrorCode.FORBIDDEN)

        val saved =
            manualExchangeRateRepository.save(
                ManualExchangeRate(
                    tripId = tripId,
                    currency = currency.name,
                    rate = rate,
                    exchangeDate = exchangeDate,
                    exchangeMethod = exchangeMethod,
                    createdByTripMemberId = recorder.tripMemberId,
                ),
            )

        return ManualExchangeRateResult(
            exchangeRateId = checkNotNull(saved.id),
            currency = saved.currency,
            rate = saved.rate,
            exchangeMethod = saved.exchangeMethod,
            exchangeDate = saved.exchangeDate,
            createdAt = checkNotNull(saved.createdAt),
        )
    }
}
