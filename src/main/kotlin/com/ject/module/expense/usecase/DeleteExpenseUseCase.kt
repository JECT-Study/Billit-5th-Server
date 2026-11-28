package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.implement.ExpenseEditValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 지출 기록자나 여행 관리자가 지출을 삭제합니다. 삭제한 지출은 지출 목록과 상세 조회에서 빠지고 여행 삭제를 막지 않습니다.
 *
 * 행은 남기고 삭제 시각과 수정 시각을 저장합니다. 품목, 품목별 부담자와 참여자별 부담액도 그대로 두므로, 부담액을 합산하는 조회는 삭제되지 않은 지출만 골라야 합니다.
 * 삭제한 지출의 생성 고유키로 다시 생성을 요청하면 409 `EXPENSE_IDEMPOTENCY_KEY_REUSED`가 됩니다.
 */
@Service
class DeleteExpenseUseCase(
    private val expenseEditValidator: ExpenseEditValidator,
    private val expenseRepository: ExpenseRepository,
    private val clock: Clock,
) {
    /**
     * 요청 권한은 [ExpenseEditValidator]를 따릅니다. 지출이 없거나 이미 삭제됐으면 404이며, 삭제 응답을 받지 못해 다시 보낸 요청도 같습니다.
     * 정산이 점유하지 않았고 유효한 수정 잠금이 없는 지출만 조건부 UPDATE로 삭제합니다. 수정 잠금 토큰을 받지 않으므로 요청한 회원의 잠금이어도 삭제하지 않습니다.
     * 삭제하지 못하면 다시 읽어 그 사이 삭제는 404, 정산 점유는 409 `EXPENSE_SETTLEMENT_LOCKED`, 나머지는 유효한 수정 잠금이 있는 것이므로
     * 409 `EXPENSE_EDIT_LOCKED`입니다.
     */
    @Transactional
    fun delete(
        memberId: Long,
        tripId: Long,
        expenseId: Long,
    ) {
        expenseEditValidator.validate(memberId = memberId, tripId = tripId, expenseId = expenseId)

        if (expenseRepository.deleteExpenseIfUnlocked(expenseId = expenseId, tripId = tripId, now = clock.instant())) {
            return
        }
        val expense = expenseRepository.findExpense(expenseId)
        throw when {
            expense == null || expense.isDeleted() -> BusinessException(CommonErrorCode.NOT_FOUND)
            expense.settlementId != null -> BusinessException(ExpenseErrorCode.SETTLEMENT_LOCKED)
            else -> BusinessException(ExpenseErrorCode.EDIT_LOCKED)
        }
    }
}
