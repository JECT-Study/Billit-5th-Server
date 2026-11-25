package com.ject.module.trip.presentation

import com.ject.global.response.ApiResponse
import com.ject.module.trip.domain.TripGuestResult
import com.ject.module.trip.presentation.request.TripGuestRequest
import com.ject.module.trip.presentation.response.TripGuestResponse
import com.ject.module.trip.usecase.DeleteTripGuestUseCase
import com.ject.module.trip.usecase.RegisterTripGuestUseCase
import com.ject.module.trip.usecase.RenameTripGuestUseCase
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/** 관리자의 비로그인 참여자 등록, 이름 수정과 삭제를 담당합니다. */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/guests")
class TripGuestController(
    private val registerTripGuestUseCase: RegisterTripGuestUseCase,
    private val renameTripGuestUseCase: RenameTripGuestUseCase,
    private val deleteTripGuestUseCase: DeleteTripGuestUseCase,
) {
    @PostMapping
    fun createTripGuest(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
        @Valid @RequestBody request: TripGuestRequest,
    ): ResponseEntity<ApiResponse<TripGuestResponse>> {
        val guest = registerTripGuestUseCase.register(memberId(accessToken), tripId, request.tripNickname)

        return ResponseEntity
            .created(URI.create("/api/v1/trips/$tripId/guests/${guest.tripMemberId}"))
            .body(ApiResponse(guest.toResponse()))
    }

    @PatchMapping("/{tripMemberId}")
    fun updateTripGuest(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
        @PathVariable tripMemberId: Long,
        @Valid @RequestBody request: TripGuestRequest,
    ): ResponseEntity<ApiResponse<TripGuestResponse>> {
        val guest = renameTripGuestUseCase.rename(memberId(accessToken), tripId, tripMemberId, request.tripNickname)

        return ResponseEntity.ok(ApiResponse(guest.toResponse()))
    }

    @DeleteMapping("/{tripMemberId}")
    fun deleteTripGuest(
        @AuthenticationPrincipal accessToken: Jwt,
        @PathVariable tripId: Long,
        @PathVariable tripMemberId: Long,
    ): ResponseEntity<Void> {
        deleteTripGuestUseCase.delete(memberId(accessToken), tripId, tripMemberId)

        return ResponseEntity.noContent().build()
    }

    private fun TripGuestResult.toResponse(): TripGuestResponse =
        TripGuestResponse(tripMemberId = tripMemberId.toString(), tripNickname = tripNickname)

    private fun memberId(accessToken: Jwt): Long = checkNotNull(accessToken.subject).toLong()
}
