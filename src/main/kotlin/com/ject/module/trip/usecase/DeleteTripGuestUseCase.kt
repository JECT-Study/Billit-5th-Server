package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자가 확정 전 여행의 비로그인 참여자를 지웁니다. 등록 인원이 한 명 줄고 총인원은 그대로라 다른 회원이 참여할 수 있습니다.
 *
 * 빈자리 행을 두지 않으므로 참여자 행을 지웁니다. 지운 참여자의 ID는 다음 참여자에게 다시 주지 않습니다.
 * 여행 행을 잠가서 확정 요청과 차례로 처리합니다. 확정이 먼저 끝나면 삭제를 거절합니다.
 */
@Service
class DeleteTripGuestUseCase(
    private val tripRepository: TripRepository,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행의 관리자가 아니면 403입니다.
     * 2. [tripMemberId]가 이 여행의 비로그인 참여자가 아니면 404입니다. 이미 지운 참여자도 같습니다.
     * 3. 확정된 여행이면 409 `TRIP_ALREADY_CONFIRMED`입니다.
     */
    @Transactional
    fun delete(
        memberId: Long,
        tripId: Long,
        tripMemberId: Long,
    ) {
        val trip = tripRepository.findTripForUpdate(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (tripRepository.findTripMember(tripId, memberId)?.role != TripMemberRole.ADMIN) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }
        val guest =
            tripRepository.findTripMembers(tripId).firstOrNull { it.id == tripMemberId && it.status == TripMemberStatus.GUEST }
                ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (trip.status == TripStatus.CONFIRMED) {
            throw BusinessException(TripErrorCode.ALREADY_CONFIRMED)
        }

        tripRepository.deleteTripMember(guest)
    }
}
