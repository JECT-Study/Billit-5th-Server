package com.ject.module.exchangerate.presentation.response

import com.ject.module.exchangerate.domain.ExchangeMethod
import java.time.Instant
import java.time.LocalDate

/** 명세의 `PersonalRate`입니다. */
data class ManualExchangeRateResponse(
    val exchangeRateId: String,
    val currency: String,
    val rate: String,
    val exchangeMethod: ExchangeMethod,
    val exchangeDate: LocalDate,
    val createdAt: Instant,
)
