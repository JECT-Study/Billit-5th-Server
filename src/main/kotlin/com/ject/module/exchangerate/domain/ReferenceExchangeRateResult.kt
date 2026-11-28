package com.ject.module.exchangerate.domain

import java.math.BigDecimal
import java.time.LocalDate

/** [rate]는 응답에 쓰는, 소수점 셋째 자리에서 반올림한 소수점 이하 2자리 값입니다. */
data class ReferenceExchangeRateResult(
    val currency: String,
    val rate: BigDecimal,
    val provider: String,
    val baseDate: LocalDate,
)
