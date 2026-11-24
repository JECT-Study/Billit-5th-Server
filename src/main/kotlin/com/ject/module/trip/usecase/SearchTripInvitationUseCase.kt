package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripInvitationSearchResult
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.implement.TripInviteCodeGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 초대 코드로 여행을 찾아 참여 전에 확인할 정보를 조회합니다. 검색만으로는 참여하거나 빈자리를 배정하지 않습니다.
 *
 * 정원이 찼거나 확정된 여행도 결과를 반환하며, 참여할 수 있는지는 앱이 등록 인원, 총인원과 확정 상태로 표시합니다.
 */
@Service
class SearchTripInvitationUseCase(
    private val tripRepository: TripRepository,
) {
    /** [inviteCode]는 앞뒤 공백을 지우고 대문자로 바꾼 값입니다. 같은 코드의 여행이 없거나 삭제됐으면 404입니다. 형식이 맞지 않는 코드는 DB를 조회하지 않고 404입니다. */
    @Transactional(readOnly = true)
    fun search(
        memberId: Long,
        inviteCode: String,
    ): TripInvitationSearchResult {
        if (!TripInviteCodeGenerator.isWellFormed(inviteCode)) {
            throw BusinessException(TripErrorCode.INVITATION_NOT_FOUND)
        }
        val trip = tripRepository.findTripByInviteCode(inviteCode) ?: throw BusinessException(TripErrorCode.INVITATION_NOT_FOUND)
        val tripId = checkNotNull(trip.id)
        val registeredMemberCount = tripRepository.countTripMembers(tripId)
        val isAlreadyJoined = tripRepository.findTripMember(tripId, memberId) != null

        return TripInvitationSearchResult(
            tripId = tripId,
            name = trip.name,
            startDate = trip.startDate,
            endDate = trip.endDate,
            registeredMemberCount = registeredMemberCount,
            memberCapacity = trip.memberCapacity,
            status = trip.status,
            isAlreadyJoined = isAlreadyJoined,
        )
    }
}
