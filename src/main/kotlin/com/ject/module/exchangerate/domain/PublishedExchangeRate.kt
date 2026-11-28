package com.ject.module.exchangerate.domain

import java.math.BigDecimal
import java.time.LocalDate

/** 고시 기관이 [baseDate]에 고시한, 외화 1단위당 원화 환율입니다. 반올림하지 않은 원본입니다. */
data class PublishedExchangeRate(
    val baseDate: LocalDate,
    val rate: BigDecimal,
)
