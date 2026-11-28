package com.ject.module.exchangerate.domain

/** 환율을 조회하거나 기록할 수 있는 외화입니다. 원화(KRW)는 환율이 항상 1이므로 포함하지 않습니다. */
enum class ExchangeCurrency {
    JPY,
    USD,
    EUR,
    CNY,
}
