package com.ject.external.exchangerate

import java.math.BigDecimal
import java.time.LocalDate

data class ExchangeRateData(
    val date: LocalDate,
    val base: String,
    val quote: String,
    val rate: BigDecimal,
    val source: String,
)
