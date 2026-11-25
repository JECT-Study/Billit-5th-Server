package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 일반 참여자가 여행에서 나갑니다. 미정산 지출이 있어도 나갈 수 있고, 나간 여행은 그 회원의 여행 목록에서 빠집니다.
 *
 * 참여 정보는 지우지 않고 나간 상태와 나간 시각만 저장해 지출과 정산에서 계속 참조합니다.
 * 여행 행을 잠가서 확정 요청과 차례로 처리합니다. 나가기가 먼저 끝나면 빈자리가 생겨 뒤의 확정은 확정하지 않습니다.
 */
@Service
class LeaveTripUseCase(
    private val tripRepository: TripRepository,
    private val clock: Clock,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404입니다.
     * 2. 요청한 회원이 이 여행에 참여 중(`MEMBER`)이 아니면 403입니다. 이미 나간 회원도 403입니다.
     * 3. 관리자는 나갈 수 없어 403입니다. 관리자는 여행을 삭제해야 여행에서 나갈 수 있습니다.
     */
    @Transactional
    fun leave(
        memberId: Long,
        tripId: Long,
    ) {
        tripRepository.findTripForUpdate(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        val me = tripRepository.findTripMember(tripId, memberId)
        if (me == null || me.status != TripMemberStatus.MEMBER || me.role == TripMemberRole.ADMIN) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }

        me.leave(clock.instant())
    }
}
