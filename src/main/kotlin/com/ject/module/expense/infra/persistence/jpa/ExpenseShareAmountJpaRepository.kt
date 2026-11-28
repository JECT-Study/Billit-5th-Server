package com.ject.module.expense.infra.persistence.jpa

import com.ject.module.expense.domain.ExpenseShareAmount
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ExpenseShareAmountJpaRepository : JpaRepository<ExpenseShareAmount, Long> {
    fun findByExpenseIdOrderByTripMemberId(expenseId: Long): List<ExpenseShareAmount>

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ExpenseShareAmount s WHERE s.expenseId = :expenseId")
    fun deleteAllByExpenseId(
        @Param("expenseId") expenseId: Long,
    ): Int
}
