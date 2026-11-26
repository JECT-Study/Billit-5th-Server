package com.ject.module.expense.domain

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
}
