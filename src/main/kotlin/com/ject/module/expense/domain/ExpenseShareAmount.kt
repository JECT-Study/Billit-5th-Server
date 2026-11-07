package com.ject.module.expense.domain

import com.ject.global.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "expense_share_amount")
class ExpenseShareAmount(
    @field:Column(name = "expense_id", nullable = false)
    val expenseId: Long,
    @field:Column(name = "trip_member_id", nullable = false)
    val tripMemberId: Long,
    @field:Column(name = "trip_id", nullable = false)
    val tripId: Long,
    @field:Column(name = "amount", nullable = false)
    val amount: Long,
    @field:Column(name = "krw_amount", nullable = false)
    val krwAmount: Long,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
