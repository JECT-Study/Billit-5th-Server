package com.ject.module.exchangerate.domain

data class ManualExchangeRateListResult(
    val items: List<ManualExchangeRateResult>,
    val totalElements: Long,
    val hasNext: Boolean,
)
