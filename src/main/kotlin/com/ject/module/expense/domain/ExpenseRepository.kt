package com.ject.module.expense.domain

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
}
