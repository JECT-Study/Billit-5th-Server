package com.ject.module.trip.presentation

import com.ject.global.response.ApiResponse
import com.ject.module.trip.presentation.request.JoinTripRequest
import com.ject.module.trip.presentation.response.TripMembershipResponse
import com.ject.module.trip.usecase.JoinTripUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 초대 코드로 여행 참여를 담당합니다.
 *
 * 사용자가 입력한 초대 코드는 앞뒤 공백을 지우고 대문자로 바꿔 넘깁니다. 저장된 초대 코드는 영문 대문자와 숫자뿐이므로 대소문자를 구분하지 않고 비교하는 것과 같습니다.
 */
@RestController
class TripInvitationController(
    private val joinTripUseCase: JoinTripUseCase,
) {
    /** 처음 참여하면 201, 이미 참여 중인 회원의 재요청이면 200으로 응답합니다. 참여 정보를 조회할 주소가 없어 `Location`은 넣지 않습니다. */
    @PostMapping("/api/v1/trip-memberships")
    fun joinTripByInviteCode(
        @AuthenticationPrincipal accessToken: Jwt,
        @Valid @RequestBody request: JoinTripRequest,
    ): ResponseEntity<ApiResponse<TripMembershipResponse>> {
        val membership = joinTripUseCase.join(memberId(accessToken), normalizeInviteCode(request.inviteCode), request.nickname)

        val status = if (membership.isNewMember) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity
            .status(status)
            .body(
                ApiResponse(
                    TripMembershipResponse(
                        tripId = membership.tripId.toString(),
                        tripMemberId = membership.tripMemberId.toString(),
                    ),
                ),
            )
    }

    private fun normalizeInviteCode(code: String): String = code.trim().uppercase()

    private fun memberId(accessToken: Jwt): Long = checkNotNull(accessToken.subject).toLong()
}
