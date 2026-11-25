package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripGuestResult
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripNicknameDuplicatedException
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자가 비로그인 참여자의 이름을 바꿉니다. 여행 확정 전과 후 모두 바꿀 수 있습니다.
 *
 * 여행 행을 잠가서 같은 참여자를 지우는 요청과 차례로 처리합니다.
 */
@Service
class RenameTripGuestUseCase(
    private val tripRepository: TripRepository,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행의 관리자가 아니면 403입니다.
     * 2. [tripMemberId]가 이 여행의 비로그인 참여자가 아니면 404입니다.
     * 3. 같은 여행의 다른 참여자가 [tripNickname]을 쓰고 있으면 409 `TRIP_NICKNAME_DUPLICATED`입니다.
     */
    @Transactional
    fun rename(
        memberId: Long,
        tripId: Long,
        tripMemberId: Long,
        tripNickname: String,
    ): TripGuestResult {
        tripRepository.findTripForUpdate(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (tripRepository.findTripMember(tripId, memberId)?.role != TripMemberRole.ADMIN) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }
        val guest =
            tripRepository.findTripMembers(tripId).firstOrNull { it.id == tripMemberId && it.status == TripMemberStatus.GUEST }
                ?: throw BusinessException(CommonErrorCode.NOT_FOUND)

        guest.changeTripNickname(tripNickname)
        try {
            tripRepository.saveParticipant(guest)
        } catch (exception: TripNicknameDuplicatedException) {
            throw BusinessException(TripErrorCode.NICKNAME_DUPLICATED, exception)
        }

        return TripGuestResult(tripMemberId = tripMemberId, tripNickname = tripNickname)
    }
}
