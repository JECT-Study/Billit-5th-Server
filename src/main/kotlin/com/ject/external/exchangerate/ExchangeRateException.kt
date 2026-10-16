package com.ject.external.exchangerate

class ExchangeRateException(
    val reason: ExchangeRateFailureReason,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    message: String = "환율 조회에 실패했습니다: $reason",
) : RuntimeException(message, cause)
