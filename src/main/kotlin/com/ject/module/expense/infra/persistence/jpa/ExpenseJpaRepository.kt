package com.ject.module.expense.infra.persistence.jpa

import com.ject.module.expense.domain.Expense
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ExpenseJpaRepository : JpaRepository<Expense, Long> {
    fun findByIdempotencyKey(idempotencyKey: UUID): Expense?
}
