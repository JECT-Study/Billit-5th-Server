package com.ject.module.expense.infra.persistence.jpa

import com.ject.module.expense.domain.ExpenseItem
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ExpenseItemJpaRepository : JpaRepository<ExpenseItem, Long> {
    fun findByExpenseIdOrderBySortOrder(expenseId: Long): List<ExpenseItem>

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ExpenseItem i WHERE i.expenseId = :expenseId")
    fun deleteAllByExpenseId(
        @Param("expenseId") expenseId: Long,
    ): Int
}
