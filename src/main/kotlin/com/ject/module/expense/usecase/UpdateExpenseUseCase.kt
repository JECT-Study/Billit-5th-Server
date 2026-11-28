package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseDetailResult
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.implement.ExpenseCalculator
import com.ject.module.expense.implement.ExpenseCreator
import com.ject.module.expense.implement.ExpenseDetailReader
import com.ject.module.expense.implement.ExpenseEditValidator
import com.ject.module.expense.implement.ExpenseShareValidator
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 수정 잠금을 가진 지출 기록자나 여행 관리자가 지출을 요청한 입력 전체로 바꾸고 수정 잠금을 해제합니다.
 *
 * 권한 확인, 지출 갱신, 품목과 부담액 교체, 잠금 해제를 한 트랜잭션으로 묶습니다. 하나라도 실패하면 모두 롤백해 기존 지출과 수정 잠금이 그대로 남습니다.
 * 수정에는 재요청을 구분하는 고유키가 없습니다. 수정에 성공하면 잠금이 풀리므로 같은 토큰으로 다시 보낸 요청은 409 `EXPENSE_EDIT_LOCK_INVALID`입니다.
 */
@Service
class UpdateExpenseUseCase(
    private val tripRepository: TripRepository,
    private val expenseRepository: ExpenseRepository,
    private val expenseEditValidator: ExpenseEditValidator,
    private val expenseShareValidator: ExpenseShareValidator,
    private val expenseCalculator: ExpenseCalculator,
    private val expenseCreator: ExpenseCreator,
    private val expenseDetailReader: ExpenseDetailReader,
    private val clock: Clock,
) {
    /**
     * 1. 요청 권한은 [ExpenseEditValidator]를 따릅니다.
     * 2. 분배 방식과 부담자 입력의 조합, 결제자와 부담자의 참여 상태는 [ExpenseShareValidator]를 따릅니다.
     *    수정 전 지출의 결제자와 부담자는 여행을 나갔거나 탈퇴했어도 그대로 둘 수 있습니다.
     * 3. 금액 검증과 부담액 계산은 생성과 같이 [ExpenseCalculator]를 따릅니다.
     * 4. 요청한 회원의 잠금이고 토큰이 같으며 만료 전이고, 정산이 점유하지 않은 지출만 조건부 UPDATE로 바꿉니다.
     *    바꾸지 못하면 다시 읽어 그 사이 삭제는 404, 정산 점유는 409 `EXPENSE_SETTLEMENT_LOCKED`, 나머지는 409 `EXPENSE_EDIT_LOCK_INVALID`입니다.
     * 5. 기존 품목, 품목별 부담자와 참여자별 부담액을 모두 지우고 요청한 값으로 다시 저장합니다. 품목 ID는 수정할 때마다 바뀝니다.
     * 6. 수정 잠금을 해제하고 수정한 지출의 상세를 반환합니다.
     *
     * 지출 갱신으로 얻은 행 잠금을 커밋까지 유지하므로, 같은 지출의 다른 수정과 정산 점유는 이 트랜잭션이 끝난 뒤의 값으로 다시 판단합니다.
     * 기록자, 지출 생성 고유키와 환율 제공 기관은 바꾸지 않습니다.
     */
    @Transactional
    fun update(
        memberId: Long,
        tripId: Long,
        expenseId: Long,
        lockToken: UUID,
        command: CreateExpenseCommand,
    ): ExpenseDetailResult {
        val requesterTripMemberId = expenseEditValidator.validate(memberId = memberId, tripId = tripId, expenseId = expenseId)
        val expense = checkNotNull(expenseRepository.findExpense(expenseId)) { "지출이 없습니다. expenseId=$expenseId" }
        expenseShareValidator.validate(
            command = command,
            participants = tripRepository.findParticipants(tripId),
            retainedPayerTripMemberId = expense.payerTripMemberId,
            retainedShareTripMemberIds = expenseRepository.findShareAmounts(expenseId).map { it.tripMemberId }.toSet(),
        )
        val calculation = expenseCalculator.calculate(command)

        val now = clock.instant()
        val updated =
            expenseRepository.updateExpenseIfEditLocked(
                expenseId = expenseId,
                tripId = tripId,
                ownerTripMemberId = requesterTripMemberId,
                token = lockToken,
                command = command,
                calculation = calculation,
                now = now,
            )
        if (!updated) {
            val current = expenseRepository.findExpense(expenseId)
            throw when {
                current == null || current.isDeleted() -> BusinessException(CommonErrorCode.NOT_FOUND)
                current.settlementId != null -> BusinessException(ExpenseErrorCode.SETTLEMENT_LOCKED)
                else -> BusinessException(ExpenseErrorCode.EDIT_LOCK_INVALID)
            }
        }
        expenseRepository.deleteItemsAndShareAmounts(expenseId)
        expenseCreator.createItemsAndShareAmounts(tripId = tripId, expenseId = expenseId, command = command, calculation = calculation)

        // 지출 행의 잠금을 이 트랜잭션이 쥐고 있어 해제는 실패할 수 없습니다. 실패하면 내부 결함이므로 500으로 수정 전체를 되돌립니다.
        val released =
            expenseRepository.releaseEditLock(
                expenseId = expenseId,
                tripId = tripId,
                ownerTripMemberId = requesterTripMemberId,
                token = lockToken,
            )
        check(released) { "지출 갱신 뒤 수정 잠금을 해제하지 못했습니다. expenseId=$expenseId" }
        return expenseDetailReader.read(expenseId)
    }
}
