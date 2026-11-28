package com.ject.module.exchangerate.presentation.response

import java.time.LocalDate

/** 명세의 `ReferenceRate`입니다. */
data class ReferenceExchangeRateResponse(
    val currency: String,
    val rate: String,
    val provider: String,
    val baseDate: LocalDate,
)
