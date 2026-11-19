package com.ject.module.trip.presentation

import com.ject.global.response.ApiResponse
import com.ject.module.trip.domain.CreateTripCommand
import com.ject.module.trip.presentation.request.CreateTripRequest
import com.ject.module.trip.presentation.response.TripSummaryResponse
import com.ject.module.trip.usecase.CreateTripUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/trips")
class TripController(
    private val createTripUseCase: CreateTripUseCase,
) {
    /** 처음 만든 요청과 같은 회원이 같은 고유키로 다시 보낸 요청 모두 201과 `Location`으로 응답합니다. */
    @PostMapping
    fun createTrip(
        @AuthenticationPrincipal accessToken: Jwt,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER) @Pattern(regexp = UUID_PATTERN) idempotencyKey: String,
        @Valid @RequestBody request: CreateTripRequest,
    ): ResponseEntity<ApiResponse<TripSummaryResponse>> {
        val command =
            CreateTripCommand(
                name = request.name.trim(),
                countryCode = request.countryCode,
                customCountryName = request.customCountryName?.trim(),
                baseCurrency = request.baseCurrency,
                startDate = request.startDate,
                endDate = request.endDate,
                memberCapacity = checkNotNull(request.memberCapacity),
                nickname = request.nickname,
            )

        val trip = createTripUseCase.create(memberId(accessToken), UUID.fromString(idempotencyKey), command)

        return ResponseEntity
            .created(URI.create("/api/v1/trips/${trip.tripId}"))
            .body(
                ApiResponse(
                    TripSummaryResponse(
                        tripId = trip.tripId.toString(),
                        name = trip.name,
                        countryCode = trip.countryCode,
                        customCountryName = trip.customCountryName,
                        startDate = trip.startDate,
                        endDate = trip.endDate,
                        baseCurrency = trip.baseCurrency,
                        memberCapacity = trip.memberCapacity,
                        registeredMemberCount = trip.registeredMemberCount,
                        status = trip.status,
                        myRole = trip.myRole,
                    ),
                ),
            )
    }

    private fun memberId(accessToken: Jwt): Long = checkNotNull(accessToken.subject).toLong()

    companion object {
        private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

        /** 8-4-4-4-12자리 16진수 표기만 받습니다. `UUID.fromString`은 자릿수가 모자란 값도 받으므로 형식을 먼저 확인합니다. */
        private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    }
}
