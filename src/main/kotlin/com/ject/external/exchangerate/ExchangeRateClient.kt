package com.ject.external.exchangerate

import java.time.LocalDate

interface ExchangeRateClient {
    fun fetch(
        base: String,
        quotes: Set<String>,
        date: LocalDate,
    ): List<ExchangeRateData>
}
