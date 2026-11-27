package com.ject.module.expense.implement

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.CreateExpenseCommand
import com.ject.module.expense.domain.ExpenseErrorCode
import com.ject.module.expense.domain.SplitType
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripParticipantResult
import org.springframework.stereotype.Component

/** 지출 입력의 결제자와 부담자가 지출에 지정할 수 있는 참여자인지 확인합니다. 지출 생성과 수정이 같은 기준을 씁니다. DB를 읽거나 쓰지 않습니다. */
@Component
class ExpenseShareValidator {
    /**
     * 1. 전체 1/N인데 tripMemberIds가 없거나 items를 보냈으면, 품목별인데 items가 없거나 비었거나 tripMemberIds를 보냈으면 400입니다.
     * 2. 결제자가 참여 중인 로그인 참여자가 아니거나, 부담자가 없거나 겹치거나 참여 중인 로그인 참여자나 비로그인 참여자가 아니면 400 `EXPENSE_INVALID_SHARES`입니다.
     *
     * 지출 수정에서는 수정 전 지출의 결제자를 [retainedPayerTripMemberId]로, 부담자를 [retainedShareTripMemberIds]로 받습니다.
     * 이 참여자는 여행을 나갔거나 탈퇴했어도 같은 역할로 그대로 둘 수 있고, 새로 지정하는 참여자에게만 2번의 참여 상태 기준을 적용합니다.
     * 품목은 수정할 때마다 다시 만들어 이전 품목과 짝지을 수 없으므로, 부담자는 품목이 아니라 지출 전체를 기준으로 봅니다.
     * 지출 생성은 그대로 둘 참여자가 없으므로 `null`과 빈 집합을 넘깁니다.
     */
    fun validate(
        command: CreateExpenseCommand,
        participants: List<TripParticipantResult>,
        retainedPayerTripMemberId: Long?,
        retainedShareTripMemberIds: Set<Long>,
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
        val isPayerValid =
            command.payerTripMemberId == retainedPayerTripMemberId || statuses[command.payerTripMemberId] == TripMemberStatus.MEMBER
        val areSharesValid =
            shareGroups.all { tripMemberIds ->
                tripMemberIds.isNotEmpty() &&
                    tripMemberIds.toSet().size == tripMemberIds.size &&
                    tripMemberIds.all { it in retainedShareTripMemberIds || statuses[it] in TripMemberStatus.REGISTERED }
            }
        if (!isPayerValid || !areSharesValid) {
            throw BusinessException(ExpenseErrorCode.INVALID_SHARES)
        }
    }
}
