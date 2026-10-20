package com.ject.module.inventory.presentation.request

import jakarta.validation.constraints.Min

data class ReserveStockRequest(
    @field:Min(1)
    val quantity: Int,
)
