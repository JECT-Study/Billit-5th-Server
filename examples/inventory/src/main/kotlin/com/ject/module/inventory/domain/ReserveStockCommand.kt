package com.ject.module.inventory.domain

import java.util.UUID

data class ReserveStockCommand(
    val stockId: UUID,
    val quantity: Int,
)
