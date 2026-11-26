package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.ExpenseEditLockResult
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.implement.ExpenseEditValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 지출 기록자나 여행 관리자가 지출 수정을 시작하며 수정 잠금을 겁니다. 잠금은 `expense` 행의 칼럼에 저장합니다. */
@Service
class AcquireExpenseEditLockUseCase(
    private val expenseEditValidator: ExpenseEditValidator,
    private val expenseRepository: ExpenseRepository,
    private val clock: Clock,
) {
    /**
     * 요청 권한은 [ExpenseEditValidator]를 따릅니다. 획득마다 새 토큰을 만들고 만료 시각은 현재 시각+[ExpenseEditLockResult.DURATION](초 단위로 내림)입니다.
     * 조건부 UPDATE로 걸지 못하면 다시 읽어 정산 점유는 409 `EXPENSE_SETTLEMENT_LOCKED`, 그 사이 삭제는 404, 나머지는 유효한 잠금이 있는 것이므로
     * 409 `EXPENSE_EDIT_LOCKED`입니다. 같은 회원의 유효한 잠금도 새 토큰으로 바꾸지 않습니다.
     */
    @Transactional
    fun acquire(
        memberId: Long,
        tripId: Long,
        expenseId: Long,
    ): ExpenseEditLockResult {
        val requesterTripMemberId = expenseEditValidator.validate(memberId, tripId, expenseId)
        val now = clock.instant()
        val lock = ExpenseEditLockResult(UUID.randomUUID(), now.plus(ExpenseEditLockResult.DURATION).truncatedTo(ChronoUnit.SECONDS))

        if (expenseRepository.acquireEditLock(expenseId, tripId, requesterTripMemberId, lock.lockToken, lock.expiresAt, now)) {
            return lock
        }
        val expense = expenseRepository.findExpense(expenseId)
        throw when {
            expense == null || expense.isDeleted() -> BusinessException(CommonErrorCode.NOT_FOUND)
            expense.settlementId != null -> BusinessException(ExpenseErrorCode.SETTLEMENT_LOCKED)
            else -> BusinessException(ExpenseErrorCode.EDIT_LOCKED)
        }
    }
}
