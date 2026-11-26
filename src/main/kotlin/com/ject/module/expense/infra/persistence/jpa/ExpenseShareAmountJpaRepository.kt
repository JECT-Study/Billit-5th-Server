package com.ject.module.expense.infra.persistence.jpa

import com.ject.module.expense.domain.ExpenseShareAmount
import org.springframework.data.jpa.repository.JpaRepository

interface ExpenseShareAmountJpaRepository : JpaRepository<ExpenseShareAmount, Long> {
    fun findByExpenseIdOrderByTripMemberId(expenseId: Long): List<ExpenseShareAmount>
}
