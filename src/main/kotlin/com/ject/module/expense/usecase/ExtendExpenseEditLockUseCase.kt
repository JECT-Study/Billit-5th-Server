package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.module.expense.domain.ExpenseEditLockResult
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.implement.ExpenseEditValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 수정 잠금을 가진 회원이 같은 수정 작업의 잠금을 연장합니다. */
@Service
class ExtendExpenseEditLockUseCase(
    private val expenseEditValidator: ExpenseEditValidator,
    private val expenseRepository: ExpenseRepository,
    private val clock: Clock,
) {
    /**
     * 요청 권한은 [ExpenseEditValidator]를 따릅니다. 요청한 회원의 잠금이고 토큰이 같으며 만료 전이면 요청할 때마다 기존 만료 시각에 [ExpenseEditLockResult.EXTENSION]을 더하고,
     * 갱신 뒤 저장된 만료 시각을 반환합니다. 아니면 409 `EXPENSE_EDIT_LOCK_INVALID`이며 만료된 잠금을 되살리지 않습니다. 연장 횟수와 총 시간의 상한은 없습니다.
     */
    @Transactional
    fun extend(
        memberId: Long,
        tripId: Long,
        expenseId: Long,
        lockToken: UUID,
    ): Instant {
        val requesterTripMemberId = expenseEditValidator.validate(memberId, tripId, expenseId)
        return expenseRepository.extendEditLock(
            expenseId,
            tripId,
            requesterTripMemberId,
            lockToken,
            ExpenseEditLockResult.EXTENSION,
            clock.instant(),
        )
            ?: throw BusinessException(ExpenseErrorCode.EDIT_LOCK_INVALID)
    }
}
