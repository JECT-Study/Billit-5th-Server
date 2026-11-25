package com.ject.module.trip.presentation

import com.ject.global.pagination.PageResponse
import com.ject.global.response.ApiResponse
import com.ject.module.trip.domain.CreateTripCommand
import com.ject.module.trip.domain.TripScheduleStatus
import com.ject.module.trip.presentation.request.CreateTripRequest
import com.ject.module.trip.presentation.request.ListTripsRequest
import com.ject.module.trip.presentation.response.ParticipantResponse
import com.ject.module.trip.presentation.response.TripDetailResponse
import com.ject.module.trip.presentation.response.TripListItemResponse
import com.ject.module.trip.presentation.response.TripSummaryResponse
import com.ject.module.trip.usecase.CreateTripUseCase
import com.ject.module.trip.usecase.GetTripDetailUseCase
import com.ject.module.trip.usecase.ListTripsUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.WebDataBinder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.InitBinder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.beans.PropertyEditorSupport
import java.math.RoundingMode
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/trips")
class TripController(
    private val createTripUseCase: CreateTripUseCase,
    private val listTripsUseCase: ListTripsUseCase,
    private val getTripDetailUseCase: GetTripDetailUseCase,
) {
    /** 빈 `filter`를 Spring 기본 변환처럼 생략(`null`)으로 처리하지 않고, 상수 이름이 아닌 값과 같이 거절합니다. */
    @InitBinder
    fun initBinder(binder: WebDataBinder) {
        binder.registerCustomEditor(
            TripScheduleStatus::class.java,
            object : PropertyEditorSupport() {
                override fun setAsText(text: String) {
                    value = TripScheduleStatus.valueOf(text)
                }
            },
        )
    }

    @GetMapping
    fun listTrips(
        @AuthenticationPrincipal accessToken: Jwt,
        @Valid request: ListTripsRequest,
    ): ResponseEntity<PageResponse<TripListItemResponse>> {
        val result = listTripsUseCase.list(memberId(accessToken), request.filter, checkNotNull(request.today), request.page, request.size)

        return ResponseEntity.ok(
            PageResponse.from(
                data =
                    result.items.map {
                        TripListItemResponse(
                            tripId = it.tripId.toString(),
                            name = it.name,
                            countryCode = it.countryCode,
                            customCountryName = it.customCountryName,
                            startDate = it.startDate,
                            endDate = it.endDate,
                            memberCapacity = it.memberCapacity,
                            registeredMemberCount = it.registeredMemberCount,
                            status = it.status,
                            scheduleStatus = it.scheduleStatus,
                            myRole = it.myRole,
                            myShareAmountKrw = it.myShareAmountKrw.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                        )
                    },
                page = request.page,
                size = request.size,
                totalElements = result.totalElements,
                hasNext = result.hasNext,
            ),
        )
    }

    @GetMapping("/{tripId}")
    fun getTrip(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
    ): ResponseEntity<ApiResponse<TripDetailResponse>> {
        val trip = getTripDetailUseCase.get(memberId(accessToken), tripId)

        return ResponseEntity.ok(
            ApiResponse(
                TripDetailResponse(
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
                    participants =
                        trip.participants.map {
                            ParticipantResponse(
                                tripMemberId = it.tripMemberId.toString(),
                                memberId = it.memberId?.toString(),
                                tripNickname = it.tripNickname,
                                role = it.role,
                                participantStatus = it.participantStatus,
                                shareAmountKrw = it.shareAmountKrw.setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                            )
                        },
                ),
            ),
        )
    }

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
                tripNickname = request.tripNickname,
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
