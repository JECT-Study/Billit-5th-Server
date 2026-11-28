package com.ject.module.exchangerate.presentation.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/** 내 환율 목록 조회 요청입니다. */
data class ListManualExchangeRatesRequest(
    @field:Min(0)
    val page: Int = 0,
    @field:Min(1)
    @field:Max(100)
    val size: Int = 20,
)
