package com.ject.module.exchangerate.presentation.request

import com.ject.module.exchangerate.domain.ExchangeCurrency
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

/** 내 환율 목록 조회 요청입니다. `currency`를 생략하면 모든 통화의 기록을 조회합니다. */
data class ListManualExchangeRatesRequest(
    val currency: ExchangeCurrency? = null,
    @field:Min(0)
    val page: Int = 0,
    @field:Min(1)
    @field:Max(100)
    val size: Int = 20,
)
