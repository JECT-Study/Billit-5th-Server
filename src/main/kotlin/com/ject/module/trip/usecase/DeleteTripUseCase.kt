package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 관리자가 여행을 삭제합니다. 다른 참여자가 있어도 삭제할 수 있고, 삭제한 여행은 모든 참여자의 목록, 상세 조회와 초대 코드에서 빠집니다.
 *
 * 행은 남기고 삭제 시각만 저장합니다. 삭제한 여행의 생성 고유키로 다시 생성을 요청하면 409 `TRIP_IDEMPOTENCY_KEY_REUSED`가 됩니다.
 * 지출 저장도 같은 여행 행을 잠그므로, 삭제와 지출 저장이 동시에 들어와도 지출이 남은 여행은 삭제되지 않습니다. 정산 기록 확인은 정산 저장 기능을 만들 때 넣습니다.
 */
@Service
class DeleteTripUseCase(
    private val tripRepository: TripRepository,
    private val expenseRepository: ExpenseRepository,
    private val clock: Clock,
) {
    /**
     * 1. 여행이 없거나 이미 삭제됐으면 404입니다. 삭제 응답을 받지 못해 다시 보낸 요청도 같습니다.
     * 2. 요청한 회원이 이 여행의 관리자가 아니면 403입니다.
     * 3. 삭제되지 않은 지출이 있으면 409 `TRIP_RECORDS_EXIST`입니다.
     */
    @Transactional
    fun delete(
        memberId: Long,
        tripId: Long,
    ) {
        val trip = tripRepository.findTripForUpdate(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (tripRepository.findTripMember(tripId, memberId)?.role != TripMemberRole.ADMIN) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }
        if (expenseRepository.existsActiveExpense(tripId)) {
            throw BusinessException(TripErrorCode.RECORDS_EXIST)
        }

        trip.delete(clock.instant())
    }
}
