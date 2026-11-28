package com.ject.module.exchangerate.presentation

import com.ject.global.response.ApiResponse
import com.ject.module.exchangerate.presentation.response.ReferenceExchangeRateResponse
import com.ject.module.exchangerate.usecase.GetReferenceExchangeRateUseCase
import jakarta.validation.constraints.Pattern
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.RoundingMode
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/reference-exchange-rates")
class ReferenceExchangeRateController(
    private val getReferenceExchangeRateUseCase: GetReferenceExchangeRateUseCase,
) {
    @GetMapping
    fun getReferenceExchangeRate(
        @RequestParam @Pattern(regexp = CURRENCY_PATTERN, message = "JPY, USD, EUR, CNY 중 하나여야 합니다.") currency: String,
        @RequestParam @DateTimeFormat(pattern = DATE_PATTERN) paidDate: LocalDate,
    ): ResponseEntity<ApiResponse<ReferenceExchangeRateResponse>> {
        val result = getReferenceExchangeRateUseCase.get(currency, paidDate)

        return ResponseEntity.ok(
            ApiResponse(
                ReferenceExchangeRateResponse(
                    currency = result.currency,
                    rate = result.rate.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    provider = result.provider,
                    baseDate = result.baseDate,
                ),
            ),
        )
    }

    companion object {
        /** 원화 환율을 조회하는 외화만 받습니다. `KRW`는 외부를 호출하지 않고 거절합니다. */
        private const val CURRENCY_PATTERN = "JPY|USD|EUR|CNY"

        /** 엄격한 형식이라 `2026-1-5`나 없는 날짜 `2026-02-30`은 받지 않습니다. */
        private const val DATE_PATTERN = "yyyy-MM-dd"
    }
}
