package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripGuestResult
import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripNicknameDuplicatedException
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자가 빈자리 하나에 비로그인 참여자 한 명을 이름으로 등록합니다.
 *
 * 여행 행을 잠근 뒤 확정 여부와 등록 인원을 확인하고 저장합니다. 여행 참여, 확정과 인원 변경도 같은 행을 잠그므로 동시에 요청해도 정원을 넘거나 확정한 여행에 등록되지 않습니다.
 */
@Service
class RegisterTripGuestUseCase(
    private val tripRepository: TripRepository,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행의 관리자가 아니면 403입니다.
     * 2. 확정된 여행이면 409 `TRIP_ALREADY_CONFIRMED`, 빈자리가 없으면 409 `TRIP_CAPACITY_FULL`입니다.
     * 3. 같은 여행의 다른 참여자가 [tripNickname]을 쓰고 있으면 409 `TRIP_NICKNAME_DUPLICATED`입니다.
     */
    @Transactional
    fun register(
        memberId: Long,
        tripId: Long,
        tripNickname: String,
    ): TripGuestResult {
        val trip = tripRepository.findTripForUpdate(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (tripRepository.findTripMember(tripId, memberId)?.role != TripMemberRole.ADMIN) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }
        if (trip.status == TripStatus.CONFIRMED) {
            throw BusinessException(TripErrorCode.ALREADY_CONFIRMED)
        }
        if (tripRepository.countTripMembers(tripId) >= trip.memberCapacity) {
            throw BusinessException(TripErrorCode.CAPACITY_FULL)
        }

        val tripMemberId =
            try {
                tripRepository.saveParticipant(
                    TripMember(
                        tripId = tripId,
                        memberId = null,
                        tripNickname = tripNickname,
                        role = TripMemberRole.MEMBER,
                        joinedAt = null,
                        status = TripMemberStatus.GUEST,
                        leftAt = null,
                    ),
                )
            } catch (exception: TripNicknameDuplicatedException) {
                throw BusinessException(TripErrorCode.NICKNAME_DUPLICATED, exception)
            }

        return TripGuestResult(tripMemberId = tripMemberId, tripNickname = tripNickname)
    }
}
