package com.ject.module.trip.presentation.request

import jakarta.validation.constraints.Pattern

/** 비로그인 참여자를 등록하거나 이름을 바꾸는 요청입니다. 명세의 `GuestInput`입니다. [tripNickname]은 공백을 지우지 않고 검사합니다. */
data class TripGuestRequest(
    @field:Pattern(regexp = TRIP_NICKNAME_PATTERN, message = TRIP_NICKNAME_MESSAGE)
    val tripNickname: String,
) {
    companion object {
        /** 한글 완성형, 영문과 숫자로 된 1~10자입니다. 계정 닉네임과 같은 규칙입니다. */
        private const val TRIP_NICKNAME_PATTERN = "[가-힣A-Za-z0-9]{1,10}"

        /** 서버 locale에 따라 달라지는 기본 메시지 대신 여행 참여 요청과 같은 고정 문구를 씁니다. */
        private const val TRIP_NICKNAME_MESSAGE = "한글, 영문, 숫자로 1~10자여야 합니다."
    }
}
