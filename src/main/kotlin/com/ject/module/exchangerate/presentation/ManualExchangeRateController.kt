package com.ject.module.exchangerate.presentation

import com.ject.global.pagination.PageResponse
import com.ject.global.response.ApiResponse
import com.ject.module.exchangerate.presentation.request.ListManualExchangeRatesRequest
import com.ject.module.exchangerate.presentation.request.RegisterManualExchangeRateRequest
import com.ject.module.exchangerate.presentation.response.ManualExchangeRateResponse
import com.ject.module.exchangerate.usecase.ListManualExchangeRatesUseCase
import com.ject.module.exchangerate.usecase.RegisterManualExchangeRateUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode

@RestController
@RequestMapping("/api/v1/trips/{tripId}/exchange-rates")
class ManualExchangeRateController(
    private val registerManualExchangeRateUseCase: RegisterManualExchangeRateUseCase,
    private val listManualExchangeRatesUseCase: ListManualExchangeRatesUseCase,
) {
    /** 개인 환율을 조회하는 API가 한 건 단위로는 없으므로 `Location` 없이 201로 응답합니다. */
    @PostMapping
    fun registerManualExchangeRate(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
        @Valid @RequestBody request: RegisterManualExchangeRateRequest,
    ): ResponseEntity<ApiResponse<ManualExchangeRateResponse>> {
        val result =
            registerManualExchangeRateUseCase.register(
                memberId = memberId(accessToken),
                tripId = tripId,
                currency = request.currency,
                rate = BigDecimal(request.rate),
                exchangeMethod = request.exchangeMethod,
                exchangeDate = request.exchangeDate,
            )

        return ResponseEntity.status(HttpStatus.CREATED).body(
            ApiResponse(
                ManualExchangeRateResponse(
                    exchangeRateId = result.exchangeRateId.toString(),
                    currency = result.currency,
                    rate = result.rate.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    exchangeMethod = result.exchangeMethod,
                    exchangeDate = result.exchangeDate,
                    createdAt = result.createdAt,
                ),
            ),
        )
    }

    @GetMapping
    fun listManualExchangeRates(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
        @Valid request: ListManualExchangeRatesRequest,
    ): ResponseEntity<PageResponse<ManualExchangeRateResponse>> {
        val result = listManualExchangeRatesUseCase.list(memberId(accessToken), tripId, request.currency, request.page, request.size)

        return ResponseEntity.ok(
            PageResponse.from(
                data =
                    result.items.map {
                        ManualExchangeRateResponse(
                            exchangeRateId = it.exchangeRateId.toString(),
                            currency = it.currency,
                            rate = it.rate.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                            exchangeMethod = it.exchangeMethod,
                            exchangeDate = it.exchangeDate,
                            createdAt = it.createdAt,
                        )
                    },
                page = request.page,
                size = request.size,
                totalElements = result.totalElements,
                hasNext = result.hasNext,
            ),
        )
    }

    private fun memberId(accessToken: Jwt): Long = checkNotNull(accessToken.subject).toLong()
}
