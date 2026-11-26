package com.ject.module.expense.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ExpenseRepository {
    /**
     * 새 지출을 저장하고 지출 ID를 반환합니다.
     *
     * - 같은 지출 생성 고유키의 지출이 이미 저장돼 있어 `uq_expense_idempotency_key` 위반이면 [ExpenseIdempotencyKeyDuplicatedException]을 던집니다. 다른 DB 오류는 그대로 던집니다.
     * - 트랜잭션: 호출하는 쪽의 트랜잭션에 참여합니다. 제약 위반이 난 트랜잭션은 더 쓸 수 없으므로 예외를 트랜잭션 밖으로 전파해 롤백합니다.
     */
    fun saveExpense(expense: Expense): Long

    /** 품목을 저장하고 품목 ID를 반환합니다. DB 오류는 그대로 던집니다. 트랜잭션은 호출하는 쪽이 엽니다. */
    fun saveItem(item: ExpenseItem): Long

    /** 품목별 부담자 연결을 저장합니다. DB 오류는 그대로 던집니다. 트랜잭션은 호출하는 쪽이 엽니다. */
    fun saveItemMembers(itemMembers: List<ExpenseItemMember>)

    /** 참여자별 최종 부담액을 저장합니다. DB 오류는 그대로 던집니다. 트랜잭션은 호출하는 쪽이 엽니다. */
    fun saveShareAmounts(shareAmounts: List<ExpenseShareAmount>)

    /** 지출 생성 고유키로 만든 지출을 조회합니다. 삭제된 지출도 반환하며, 그 키로 만든 지출이 없으면 `null`입니다. */
    fun findExpenseByIdempotencyKey(idempotencyKey: UUID): Expense?

    /** 지출을 조회합니다. 삭제된 지출도 반환하며, 없으면 `null`입니다. */
    fun findExpense(expenseId: Long): Expense?

    /** 지출의 품목을 표시 순서대로 조회합니다. */
    fun findItems(expenseId: Long): List<ExpenseItem>

    /** 지출의 모든 품목별 부담자 연결을 조회합니다. 순서는 보장하지 않습니다. */
    fun findItemMembers(expenseId: Long): List<ExpenseItemMember>

    /** 지출의 참여자별 최종 부담액을 여행 참여자 ID 순서대로 조회합니다. */
    fun findShareAmounts(expenseId: Long): List<ExpenseShareAmount>

    /**
     * 여행의 삭제되지 않은 지출을 결제 날짜의 역순, 같으면 지출 ID의 역순으로 [offset]부터 [limit]개 조회합니다.
     *
     * 결제 날짜가 [fromDate]부터 [toDate]까지(양 끝 포함)인 지출만 조회하며, 날짜가 `null`이면 그 경계를 두지 않습니다.
     */
    fun findExpenses(
        tripId: Long,
        fromDate: LocalDate?,
        toDate: LocalDate?,
        offset: Int,
        limit: Int,
    ): List<Expense>

    /** [findExpenses]와 같은 조건에 맞는 전체 지출 수를 조회합니다. */
    fun countExpenses(
        tripId: Long,
        fromDate: LocalDate?,
        toDate: LocalDate?,
    ): Long

    /**
     * 정산이 점유하지 않았고 [now]보다 늦게 만료되는 잠금이 없는 지출에 잠금을 걸면 `true`입니다. 만료된 잠금은 소유자와 관계없이 바꾸고, 같은 소유자의 유효한 잠금은 바꾸지 않습니다.
     * 잠금 칼럼만 바꾸며 수정 시각(`updated_at`)은 그대로 둡니다. 호출하는 쪽의 트랜잭션에서 조건부 UPDATE 한 번으로 실행해 경쟁하는 요청 중 하나만 성공하며,
     * 실행 뒤 영속성 컨텍스트를 비우므로 이후 조회는 DB 값을 읽습니다.
     */
    fun acquireEditLock(
        expenseId: Long,
        tripId: Long,
        ownerTripMemberId: Long,
        token: UUID,
        expiresAt: Instant,
        now: Instant,
    ): Boolean

    /**
     * 소유자와 토큰이 같고 [now]보다 늦게 만료되는 잠금의 만료 시각에 [extension]을 더하고, 갱신 뒤 DB에 저장된 만료 시각을 반환합니다.
     * 잠금이 없거나 소유자나 토큰이 다르거나 만료됐으면 `null`입니다. 수정 시각과 트랜잭션은 [acquireEditLock]과 같습니다.
     */
    fun extendEditLock(
        expenseId: Long,
        tripId: Long,
        ownerTripMemberId: Long,
        token: UUID,
        extension: Duration,
        now: Instant,
    ): Instant?

    /** 소유자와 토큰이 같은 잠금을 만료 여부와 관계없이 비우면 `true`입니다. 수정 시각과 트랜잭션은 [acquireEditLock]과 같습니다. */
    fun releaseEditLock(
        expenseId: Long,
        tripId: Long,
        ownerTripMemberId: Long,
        token: UUID,
    ): Boolean
}
