package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripInvitationResult
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import com.ject.module.trip.implement.TripInvitationUrlCreator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 관리자가 공유할 초대 코드와 초대 링크를 조회합니다. 초대 코드는 여행을 만들 때 발급하며 재발급하지 않습니다. */
@Service
class GetTripInvitationUseCase(
    private val tripRepository: TripRepository,
    private val tripInvitationUrlCreator: TripInvitationUrlCreator,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404입니다.
     * 2. 요청한 회원이 이 여행의 관리자가 아니면 403입니다. 참여하지 않은 회원도 같습니다.
     * 3. 확정된 여행은 초대가 끝났으므로 409입니다.
     */
    @Transactional(readOnly = true)
    fun get(
        memberId: Long,
        tripId: Long,
    ): TripInvitationResult {
        val trip = tripRepository.findTrip(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        val requester = tripRepository.findTripMember(tripId, memberId)
        if (requester?.role != TripMemberRole.ADMIN) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }
        if (trip.status == TripStatus.CONFIRMED) {
            throw BusinessException(TripErrorCode.ALREADY_CONFIRMED)
        }

        return TripInvitationResult(
            inviteCode = trip.inviteCode,
            inviteUrl = tripInvitationUrlCreator.create(trip.inviteCode),
        )
    }
}
