package com.ject.module.exchangerate.domain

import com.ject.global.error.ErrorCode

enum class ExchangeRateErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    NOT_PUBLISHED(404, "선택한 날짜 전후에 고시된 환율이 없습니다. 환율을 직접 입력해 주세요."),
    PROVIDER_UNAVAILABLE(503, "환율을 일시적으로 조회할 수 없습니다. 환율을 직접 입력하거나 잠시 후 다시 시도해 주세요."),
    ;

    override val code: String = "EXCHANGE_RATE_$name"
}
