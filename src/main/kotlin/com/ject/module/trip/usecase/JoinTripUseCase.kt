package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripMembershipResult
import com.ject.module.trip.domain.TripNicknameDuplicatedException
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import com.ject.module.trip.implement.TripInviteCodeGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 초대 코드와 여행방 닉네임으로 여행에 참여합니다. 이미 참여 중인 회원은 새로 등록하지 않고 기존 참여 정보를 반환합니다.
 *
 * 여행 행을 잠근 뒤 참여 여부, 확정 여부와 참여자 수를 확인하고 저장합니다. 같은 여행의 참여 요청은 이 잠금으로 차례로 처리되므로
 * 동시에 요청해도 정원을 넘거나 같은 회원이 두 번 등록되지 않습니다. 잠금을 얻은 뒤의 조회는 앞선 요청이 커밋한 참여자까지 봅니다.
 * 나간 회원의 재참여는 여행 나가기를 구현할 때 함께 처리합니다.
 */
@Service
class JoinTripUseCase(
    private val tripRepository: TripRepository,
    private val clock: Clock,
) {
    /**
     * [inviteCode]는 앞뒤 공백을 지우고 대문자로 바꾼 값입니다.
     *
     * 1. 같은 코드의 여행이 없거나 삭제됐으면 404입니다. 형식이 맞지 않는 코드는 DB를 조회하지 않고 404입니다. PostgreSQL이 받지 못하는 NUL 문자 같은 입력이 500으로 끝나지 않게 합니다.
     * 2. 이미 참여 중이면 확정 여부, 정원과 [tripNickname]에 관계없이 기존 참여 정보를 반환합니다.
     * 3. 확정된 여행이면 409 `TRIP_ALREADY_CONFIRMED`, 빈자리가 없으면 409 `TRIP_CAPACITY_FULL`입니다.
     * 4. 같은 여행의 다른 참여자가 [tripNickname]을 쓰고 있으면 409 `TRIP_NICKNAME_DUPLICATED`입니다.
     */
    @Transactional
    fun join(
        memberId: Long,
        inviteCode: String,
        tripNickname: String,
    ): TripMembershipResult {
        if (!TripInviteCodeGenerator.isWellFormed(inviteCode)) {
            throw BusinessException(TripErrorCode.INVITATION_NOT_FOUND)
        }
        val trip = tripRepository.findTripByInviteCodeForUpdate(inviteCode) ?: throw BusinessException(TripErrorCode.INVITATION_NOT_FOUND)
        val tripId = checkNotNull(trip.id)

        val joinedMember = tripRepository.findTripMember(tripId, memberId)
        if (joinedMember != null) {
            return TripMembershipResult(tripId = tripId, tripMemberId = checkNotNull(joinedMember.id), isNewMember = false)
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
                        memberId = memberId,
                        tripNickname = tripNickname,
                        role = TripMemberRole.MEMBER,
                        joinedAt = clock.instant(),
                        status = TripMemberStatus.MEMBER,
                        leftAt = null,
                    ),
                )
            } catch (exception: TripNicknameDuplicatedException) {
                throw BusinessException(TripErrorCode.NICKNAME_DUPLICATED, exception)
            }

        return TripMembershipResult(tripId = tripId, tripMemberId = tripMemberId, isNewMember = true)
    }
}
