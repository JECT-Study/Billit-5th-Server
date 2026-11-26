package com.ject.module.expense.infra.persistence.jpa

import com.ject.module.expense.domain.ExpenseItemMember
import org.springframework.data.jpa.repository.JpaRepository

interface ExpenseItemMemberJpaRepository : JpaRepository<ExpenseItemMember, Long> {
    fun findByExpenseId(expenseId: Long): List<ExpenseItemMember>
}
