package com.ject.module.expense.infra.persistence.jpa

import com.ject.module.expense.domain.ExpenseItem
import org.springframework.data.jpa.repository.JpaRepository

interface ExpenseItemJpaRepository : JpaRepository<ExpenseItem, Long> {
    fun findByExpenseIdOrderBySortOrder(expenseId: Long): List<ExpenseItem>
}
