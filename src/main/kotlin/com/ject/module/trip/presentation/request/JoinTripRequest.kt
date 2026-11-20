package com.ject.module.trip.presentation.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

/**
 * 초대 코드로 여행에 참여하는 요청입니다. 명세의 `InviteCodeInput`입니다.
 *
 * [inviteCode]는 비어 있지만 않으면 형식을 따지지 않습니다. 앞뒤 공백을 지우고 대문자로 바꾼 값으로 여행을 찾으며, 형식이 맞지 않으면 없는 코드와 같이 404로 응답합니다.
 * [nickname]은 이 여행에서 쓸 여행방 닉네임이며 공백을 지우지 않고 검사합니다.
 */
data class JoinTripRequest(
    @field:NotBlank
    val inviteCode: String,
    @field:Pattern(regexp = NICKNAME_PATTERN, message = NICKNAME_MESSAGE)
    val nickname: String,
) {
    companion object {
        /** 한글 완성형, 영문과 숫자로 된 1~10자입니다. 계정 닉네임과 같은 규칙입니다. */
        private const val NICKNAME_PATTERN = "[가-힣A-Za-z0-9]{1,10}"

        /** 명세 예제와 같은 사유를 응답하도록 서버 locale에 따라 달라지는 기본 메시지 대신 고정 문구를 씁니다. */
        private const val NICKNAME_MESSAGE = "한글, 영문, 숫자로 1~10자여야 합니다."
    }
}
