package com.ject.module.exchangerate.presentation.request

import com.ject.module.exchangerate.domain.ExchangeCurrency
import com.ject.module.exchangerate.domain.ExchangeMethod
import jakarta.validation.constraints.Pattern
import java.time.LocalDate

/**
 * 개인 환율 등록 요청입니다. 명세의 `PersonalRateInput`입니다.
 *
 * 환율은 정수부 10자리 이하, 소수점 이하 2자리 문자열인지만 검사하고, 0보다 큰지는 UseCase가 확인합니다.
 */
data class RegisterManualExchangeRateRequest(
    val currency: ExchangeCurrency,
    @field:Pattern(regexp = RATE_PATTERN, message = "정수부 10자리 이하, 소수점 이하 2자리 문자열이어야 합니다.")
    val rate: String,
    val exchangeMethod: ExchangeMethod,
    val exchangeDate: LocalDate,
) {
    companion object {
        private const val RATE_PATTERN = "[0-9]{1,10}\\.[0-9]{2}"
    }
}
