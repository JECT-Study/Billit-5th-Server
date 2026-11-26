package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.ExpenseDetailResult
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.implement.ExpenseDetailReader
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** 참여 중인 여행의 지출 상세를 조회합니다. 정산에 포함된 지출도 조회할 수 있습니다. */
@Service
class GetExpenseDetailUseCase(
    private val tripRepository: TripRepository,
    private val expenseRepository: ExpenseRepository,
    private val expenseDetailReader: ExpenseDetailReader,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행에 참여 중(`MEMBER`)이 아니면 403입니다. 나간 회원도 403입니다.
     * 2. 지출이 없거나 삭제됐거나 이 여행의 지출이 아니면 404입니다.
     *
     * 확인과 상세 조회가 같은 시점의 데이터를 보도록 [ExpenseDetailReader]와 같은 `REPEATABLE READ` 읽기 전용 트랜잭션에서 조회합니다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(
        memberId: Long,
        tripId: Long,
        expenseId: Long,
    ): ExpenseDetailResult {
        tripRepository.findTripStatus(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (tripRepository.findParticipants(tripId).none { it.memberId == memberId && it.status == TripMemberStatus.MEMBER }) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }
        val expense = expenseRepository.findExpense(expenseId)
        if (expense == null || expense.isDeleted() || expense.tripId != tripId) {
            throw BusinessException(CommonErrorCode.NOT_FOUND)
        }
        return expenseDetailReader.read(expenseId)
    }
}
