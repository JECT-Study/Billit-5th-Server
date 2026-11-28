package com.ject.module.expense.implement

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Component

/** 요청한 회원이 지출을 수정하거나 삭제할 수 있는 참여자인지 확인합니다. 수정 잠금의 획득, 연장, 해제와 지출 수정, 삭제가 같은 기준을 씁니다. */
@Component
class ExpenseEditValidator(
    private val tripRepository: TripRepository,
    private val expenseRepository: ExpenseRepository,
) {
    /**
     * 확인을 통과하면 요청한 회원의 여행 참여자 ID를 반환합니다.
     *
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행에 참여 중(`MEMBER`)이 아니면 403입니다.
     * 2. 지출이 없거나 삭제됐거나 이 여행의 지출이 아니면 404입니다.
     * 3. 요청한 회원이 지출 기록자도 여행 관리자도 아니면 403입니다. 결제자라는 이유만으로는 허용하지 않습니다.
     */
    fun validate(
        memberId: Long,
        tripId: Long,
        expenseId: Long,
    ): Long {
        tripRepository.findTripStatus(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        val requester =
            tripRepository.findParticipants(tripId).firstOrNull { it.memberId == memberId && it.status == TripMemberStatus.MEMBER }
                ?: throw BusinessException(CommonErrorCode.FORBIDDEN)
        val expense = expenseRepository.findExpense(expenseId)
        if (expense == null || expense.isDeleted() || expense.tripId != tripId) {
            throw BusinessException(CommonErrorCode.NOT_FOUND)
        }
        if (expense.createdByTripMemberId != requester.tripMemberId && requester.role != TripMemberRole.ADMIN) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }
        return requester.tripMemberId
    }
}
