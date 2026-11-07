package com.ject.module.settlement.domain

import com.ject.global.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "settlement_expense")
class SettlementExpense(
    @field:Column(name = "settlement_id", nullable = false)
    val settlementId: Long,
    @field:Column(name = "expense_id", nullable = false)
    val expenseId: Long,
    @field:Column(name = "trip_id", nullable = false)
    val tripId: Long,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
