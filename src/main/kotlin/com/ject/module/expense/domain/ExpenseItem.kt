package com.ject.module.expense.domain

import com.ject.global.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "expense_item")
class ExpenseItem(
    @field:Column(name = "expense_id", nullable = false)
    val expenseId: Long,
    @field:Column(name = "name", nullable = true)
    val name: String?,
    @field:Column(name = "amount", nullable = false)
    val amount: Long,
    @field:Column(name = "discount_amount", nullable = false)
    val discountAmount: Long,
    @field:Column(name = "sort_order", nullable = false)
    val sortOrder: Int,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
