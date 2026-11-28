package com.ject.module.exchangerate.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.exchangerate.domain.ManualExchangeRateRepository
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service

/**
 * 여행에 참여 중인 회원이 본인이 그 여행에 등록한 개인 환율을 삭제합니다.
 *
 * 동시 삭제는 사전 조회가 아니라 삭제 한 문장의 결과로 가르므로, 이 UseCase는 트랜잭션을 열지 않습니다.
 * 지출은 환율 값을 복사해 저장하므로 기록을 지워도 기존 지출과 정산은 바뀌지 않습니다.
 */
@Service
class DeleteManualExchangeRateUseCase(
    private val tripRepository: TripRepository,
    private val manualExchangeRateRepository: ManualExchangeRateRepository,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행에 참여 중(`MEMBER`)이 아니면 403입니다. 나간 회원도 403입니다.
     * 2. 기록 ID, 여행과 요청한 회원의 여행 참여자가 모두 같은 기록을 지웁니다.
     *    기록이 없거나 이미 지웠거나 다른 참여자가 등록한 기록이면 존재를 알리지 않도록 모두 404입니다.
     */
    fun delete(
        memberId: Long,
        tripId: Long,
        exchangeRateId: Long,
    ) {
        tripRepository.findTripStatus(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        val recorder =
            tripRepository.findParticipants(tripId).firstOrNull { it.memberId == memberId && it.status == TripMemberStatus.MEMBER }
                ?: throw BusinessException(CommonErrorCode.FORBIDDEN)

        if (!manualExchangeRateRepository.deleteRecord(exchangeRateId, tripId, recorder.tripMemberId)) {
            throw BusinessException(CommonErrorCode.NOT_FOUND)
        }
    }
}
