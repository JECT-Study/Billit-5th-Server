package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseDetailResult
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.ExpenseIdempotencyKeyDuplicatedException
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.expense.domain.SplitType
import com.ject.module.expense.implement.ExpenseCalculator
import com.ject.module.expense.implement.ExpenseCreator
import com.ject.module.expense.implement.ExpenseDetailReader
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripParticipantResult
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 확정된 여행에 참여 중인 회원이 지출을 등록합니다. 같은 회원이 같은 고유키로 다시 요청하면 새로 만들지 않고 처음 만든 지출의 현재 상세를 반환합니다.
 *
 * 이 UseCase는 전체를 묶는 트랜잭션을 열지 않습니다. 같은 고유키의 요청이 동시에 오면 늦은 요청은 유니크 제약 위반으로 실패하는데,
 * PostgreSQL은 그 트랜잭션에서 더 조회할 수 없습니다. 그래서 저장만 [ExpenseCreator]의 트랜잭션으로 묶고, 위반이 나면 그 트랜잭션이 롤백된 뒤 먼저 저장된 지출을 다시 조회합니다.
 * 여행과 참여자는 여행 업무의 Repository에서 값으로 받습니다.
 */
@Service
class CreateExpenseUseCase(
    private val tripRepository: TripRepository,
    private val expenseRepository: ExpenseRepository,
    private val expenseCalculator: ExpenseCalculator,
    private val expenseCreator: ExpenseCreator,
    private val expenseDetailReader: ExpenseDetailReader,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행에 참여 중(`MEMBER`)이 아니면 403입니다. 나간 회원도 403입니다.
     * 2. 고유키로 만든 지출이 이미 있으면 저장하지 않습니다. 이 여행에서 요청한 회원이 기록한 삭제되지 않은 지출이면 그 지출의 현재 상세를 반환하고,
     *    아니면 409 `EXPENSE_IDEMPOTENCY_KEY_REUSED`입니다. 요청 내용은 처음 요청과 비교하지 않습니다.
     * 3. 확정 전 여행이면 409 `EXPENSE_TRIP_NOT_CONFIRMED`입니다.
     * 4. 전체 1/N인데 tripMemberIds가 없거나 items를 보냈으면, 품목별인데 items가 없거나 비었거나 tripMemberIds를 보냈으면 400입니다.
     * 5. 결제자가 참여 중인 로그인 참여자가 아니거나, 부담자가 없거나 겹치거나 참여 중인 로그인 참여자나 비로그인 참여자가 아니면 400 `EXPENSE_INVALID_SHARES`입니다.
     * 6. 금액 검증과 부담액 계산은 [ExpenseCalculator]를 따르고, 요청한 회원을 기록자로 지출을 저장합니다.
     * 7. 저장이 고유키 중복으로 실패하면 같은 키의 요청이 먼저 저장된 것이므로 2번처럼 처리합니다.
     */
    fun create(
        memberId: Long,
        tripId: Long,
        idempotencyKey: UUID,
        command: CreateExpenseCommand,
    ): ExpenseDetailResult {
        val tripStatus = tripRepository.findTripStatus(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        val participants = tripRepository.findParticipants(tripId)
        val recorder =
            participants.firstOrNull { it.memberId == memberId && it.status == TripMemberStatus.MEMBER }
                ?: throw BusinessException(CommonErrorCode.FORBIDDEN)

        val createdExpenseId = findCreatedExpenseId(tripId, recorder.tripMemberId, idempotencyKey)
        if (createdExpenseId != null) {
            return expenseDetailReader.read(createdExpenseId)
        }

        if (tripStatus != TripStatus.CONFIRMED) {
            throw BusinessException(ExpenseErrorCode.TRIP_NOT_CONFIRMED)
        }
        validateShares(command, participants)
        val calculation = expenseCalculator.calculate(command)

        val expenseId =
            try {
                expenseCreator.create(tripId, recorder.tripMemberId, idempotencyKey, command, calculation)
            } catch (exception: ExpenseIdempotencyKeyDuplicatedException) {
                // 위반 뒤 조회했는데 지출이 없으면 먼저 저장된 지출을 확인할 수 없으므로 재사용한 키와 같이 거절합니다.
                findCreatedExpenseId(tripId, recorder.tripMemberId, idempotencyKey)
                    ?: throw BusinessException(ExpenseErrorCode.IDEMPOTENCY_KEY_REUSED, exception)
            }
        return expenseDetailReader.read(expenseId)
    }

    private fun validateShares(
        command: CreateExpenseCommand,
        participants: List<TripParticipantResult>,
    ) {
        val shareGroups =
            when (command.splitType) {
                SplitType.EQUAL -> {
                    if (command.tripMemberIds == null || command.items != null) {
                        throw BusinessException(CommonErrorCode.INVALID_REQUEST)
                    }
                    listOf(command.tripMemberIds)
                }

                SplitType.ITEM -> {
                    if (command.items.isNullOrEmpty() || command.tripMemberIds != null) {
                        throw BusinessException(CommonErrorCode.INVALID_REQUEST)
                    }
                    command.items.map { it.tripMemberIds }
                }
            }

        val statuses = participants.associate { it.tripMemberId to it.status }
        // 명세에 결제자 전용 오류 코드가 없고 결제자도 지출에 지정하는 참여자이므로, 부담자와 같은 코드로 거절합니다.
        val isPayerValid = statuses[command.payerTripMemberId] == TripMemberStatus.MEMBER
        // 부담자는 참여 중인 로그인 참여자(MEMBER)와 비로그인 참여자(GUEST)만 될 수 있습니다.
        val areSharesValid =
            shareGroups.all { tripMemberIds ->
                tripMemberIds.isNotEmpty() &&
                    tripMemberIds.toSet().size == tripMemberIds.size &&
                    tripMemberIds.all { statuses[it] in TripMemberStatus.REGISTERED }
            }
        if (!isPayerValid || !areSharesValid) {
            throw BusinessException(ExpenseErrorCode.INVALID_SHARES)
        }
    }

    /** 고유키로 만든 지출이 없으면 `null`을 반환합니다. 이 여행에서 요청한 회원이 기록한 삭제되지 않은 지출이 아니면 기존 지출 정보를 주지 않고 409로 거절합니다. */
    private fun findCreatedExpenseId(
        tripId: Long,
        recorderTripMemberId: Long,
        idempotencyKey: UUID,
    ): Long? {
        val expense = expenseRepository.findExpenseByIdempotencyKey(idempotencyKey) ?: return null
        if (expense.isDeleted() || expense.tripId != tripId || expense.createdByTripMemberId != recorderTripMemberId) {
            throw BusinessException(ExpenseErrorCode.IDEMPOTENCY_KEY_REUSED)
        }
        return checkNotNull(expense.id)
    }
}
