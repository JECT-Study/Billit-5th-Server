package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.implement.ExpenseEditValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** 수정 화면을 나가는 회원이 같은 수정 작업의 잠금을 해제합니다. */
@Service
class ReleaseExpenseEditLockUseCase(
    private val expenseEditValidator: ExpenseEditValidator,
    private val expenseRepository: ExpenseRepository,
    private val clock: Clock,
) {
    /**
     * 요청 권한은 [ExpenseEditValidator]를 따릅니다. 요청한 회원의 잠금이고 토큰이 같으면 만료 여부와 관계없이 비웁니다.
     * 비우지 못했을 때 잠금이 없거나 만료됐으면 같은 요청을 다시 보낸 것으로 보고 성공으로 끝내고,
     * 다른 토큰이나 다른 회원의 유효한 잠금이면 새 수정 작업의 잠금이므로 바꾸지 않고 409 `EXPENSE_EDIT_LOCK_INVALID`입니다.
     */
    @Transactional
    fun release(
        memberId: Long,
        tripId: Long,
        expenseId: Long,
        lockToken: UUID,
    ) {
        val requesterTripMemberId = expenseEditValidator.validate(memberId, tripId, expenseId)
        val now = clock.instant()

        if (expenseRepository.releaseEditLock(expenseId, tripId, requesterTripMemberId, lockToken)) {
            return
        }
        val expiresAt = expenseRepository.findExpense(expenseId)?.editLockExpiresAt
        if (expiresAt != null && expiresAt.isAfter(now)) {
            throw BusinessException(ExpenseErrorCode.EDIT_LOCK_INVALID)
        }
    }
}
