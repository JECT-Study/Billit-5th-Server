package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.TripConfirmationResult
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자가 빈자리가 없는 여행의 참여자 구성을 확정합니다. 확정한 여행은 확정 전으로 되돌리지 않습니다.
 *
 * 여행 행을 잠근 뒤 등록 인원을 확인하므로, 나가기나 비로그인 참여자 삭제처럼 빈자리를 만드는 요청이 먼저 끝나면 확정하지 않습니다.
 */
@Service
class ConfirmTripUseCase(
    private val tripRepository: TripRepository,
    private val clock: Clock,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행의 관리자가 아니면 403입니다.
     * 2. 확정 전이고 빈자리가 없으면 확정 상태와 확정 시각을 저장합니다.
     * 3. 빈자리가 남았으면 바꾸지 않고 `UNCONFIRMED`, 이미 확정된 여행이면 그대로 `CONFIRMED`를 반환합니다.
     */
    @Transactional
    fun confirm(
        memberId: Long,
        tripId: Long,
    ): TripConfirmationResult {
        val trip = tripRepository.findTripForUpdate(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (tripRepository.findTripMember(tripId, memberId)?.role != TripMemberRole.ADMIN) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }
        if (trip.status == TripStatus.UNCONFIRMED && tripRepository.countTripMembers(tripId) == trip.memberCapacity) {
            trip.confirm(clock.instant())
        }

        return TripConfirmationResult(tripId = tripId, status = trip.status)
    }
}
