package com.ject.module.exchangerate.domain

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 등록자가 기록한 개인 환율입니다. [createdAt]은 최초 등록 시각이며 최근 기록을 고르는 기준입니다. */
data class ManualExchangeRateResult(
    val exchangeRateId: Long,
    val currency: String,
    val rate: BigDecimal,
    val exchangeMethod: ExchangeMethod,
    val exchangeDate: LocalDate,
    val createdAt: Instant,
)
