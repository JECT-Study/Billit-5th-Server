package com.ject.module.inventory.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "learning_stocks")
class Stock(
    @Id val id: UUID,
    val name: String,
    remainingQuantity: Int,
) {
    @Column(name = "remaining_quantity")
    var remainingQuantity: Int = remainingQuantity
        protected set

    fun decrease(quantity: Int) {
        remainingQuantity -= quantity
    }
}
