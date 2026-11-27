package com.ject.module.expense.infra.persistence.jpa

import com.ject.module.expense.domain.ExpenseItemMember
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ExpenseItemMemberJpaRepository : JpaRepository<ExpenseItemMember, Long> {
    fun findByExpenseId(expenseId: Long): List<ExpenseItemMember>

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM ExpenseItemMember m WHERE m.expenseId = :expenseId")
    fun deleteAllByExpenseId(
        @Param("expenseId") expenseId: Long,
    ): Int
}
