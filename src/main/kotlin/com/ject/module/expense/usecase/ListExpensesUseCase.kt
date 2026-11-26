package com.ject.module.expense.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.expense.domain.ExpenseListResult
import com.ject.module.expense.domain.ExpenseRepository
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/** 참여 중인 여행의 지출을 페이지 단위로 조회합니다. 삭제된 지출은 포함하지 않습니다. */
@Service
class ListExpensesUseCase(
    private val tripRepository: TripRepository,
    private val expenseRepository: ExpenseRepository,
    private val clock: Clock,
) {
    /**
     * 1. [fromDate]가 [toDate]보다 뒤면 400입니다.
     * 2. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행에 참여 중(`MEMBER`)이 아니면 403입니다.
     * 3. 결제 날짜가 [fromDate]부터 [toDate]까지(양 끝 포함)인 지출을 결제 날짜의 역순, 같으면 지출 ID의 역순으로 정렬해 페이지로 나눕니다.
     *    수정 잠금은 조회 시점에 만료되지 않았을 때만 잠긴 것으로 봅니다.
     *
     * 전체 건수와 페이지 항목이 같은 시점의 데이터를 보도록 `REPEATABLE READ` 읽기 전용 트랜잭션 하나로 조회합니다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(
        memberId: Long,
        tripId: Long,
        fromDate: LocalDate?,
        toDate: LocalDate?,
        page: Int,
        size: Int,
    ): ExpenseListResult {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }
        tripRepository.findTripStatus(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (tripRepository.findParticipants(tripId).none { it.memberId == memberId && it.status == TripMemberStatus.MEMBER }) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }

        val offset = page.toLong() * size
        val totalElements = expenseRepository.countExpenses(tripId, fromDate, toDate)
        // 조회 시작 위치가 전체 건수 이상이거나 JPA가 받는 Int 범위를 넘으면 꺼낼 지출이 없으므로 조회하지 않습니다.
        val expenses =
            if (offset >= totalElements || offset > Int.MAX_VALUE) {
                emptyList()
            } else {
                expenseRepository.findExpenses(tripId, fromDate, toDate, offset.toInt(), size)
            }
        val now = clock.instant()

        return ExpenseListResult(
            items =
                expenses.map {
                    ExpenseListResult.Item(
                        expenseId = checkNotNull(it.id),
                        name = it.name,
                        paidDate = it.paidDate,
                        currency = it.currency,
                        amount = BigDecimal.valueOf(it.amount, it.currency.fractionDigits),
                        payerTripMemberId = it.payerTripMemberId,
                        settlementId = it.settlementId,
                        isEditLocked = it.editLockExpiresAt?.isAfter(now) == true,
                    )
                },
            totalElements = totalElements,
            hasNext = offset + size < totalElements,
        )
    }
}
