package com.ject.module.exchangerate.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.exchangerate.domain.ExchangeCurrency
import com.ject.module.exchangerate.domain.ManualExchangeRateListResult
import com.ject.module.exchangerate.domain.ManualExchangeRateRepository
import com.ject.module.exchangerate.domain.ManualExchangeRateResult
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** 여행에 참여 중인 회원이 본인이 그 여행에 등록한 개인 환율을 통화로 걸러 페이지 단위로 조회합니다. 다른 참여자의 기록은 조회할 수 없습니다. */
@Service
class ListManualExchangeRatesUseCase(
    private val tripRepository: TripRepository,
    private val manualExchangeRateRepository: ManualExchangeRateRepository,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행에 참여 중(`MEMBER`)이 아니면 403입니다. 나간 회원도 403입니다.
     * 2. 요청한 회원의 여행 참여자가 등록한 기록만 조회합니다. `currency`를 지정하면 그 통화의 기록만, 생략하면 모든 통화의 기록을
     *    최초 등록 시각의 역순, 같으면 ID의 역순으로 정렬해 페이지로 나눕니다. 조건에 맞는 기록이 없으면 빈 목록입니다.
     *    환전 날짜와 수정 시각은 순서에 영향을 주지 않습니다.
     *
     * 전체 건수와 페이지 항목이 같은 시점의 데이터를 보도록 `REPEATABLE READ` 읽기 전용 트랜잭션 하나로 조회합니다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(
        memberId: Long,
        tripId: Long,
        currency: ExchangeCurrency?,
        page: Int,
        size: Int,
    ): ManualExchangeRateListResult {
        tripRepository.findTripStatus(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        val recorder =
            tripRepository.findParticipants(tripId).firstOrNull { it.memberId == memberId && it.status == TripMemberStatus.MEMBER }
                ?: throw BusinessException(CommonErrorCode.FORBIDDEN)

        val offset = page.toLong() * size
        val totalElements = manualExchangeRateRepository.countRecords(tripId, recorder.tripMemberId, currency)
        // 조회 시작 위치가 전체 건수 이상이거나 JPA가 받는 Int 범위를 넘으면 꺼낼 기록이 없으므로 조회하지 않습니다.
        val records =
            if (offset >= totalElements || offset > Int.MAX_VALUE) {
                emptyList()
            } else {
                manualExchangeRateRepository.findRecords(tripId, recorder.tripMemberId, currency, page, size)
            }

        return ManualExchangeRateListResult(
            items =
                records.map {
                    ManualExchangeRateResult(
                        exchangeRateId = checkNotNull(it.id),
                        currency = it.currency,
                        rate = it.rate,
                        exchangeMethod = it.exchangeMethod,
                        exchangeDate = it.exchangeDate,
                        createdAt = checkNotNull(it.createdAt),
                    )
                },
            totalElements = totalElements,
            hasNext = offset + size < totalElements,
        )
    }
}
