package com.ject.module.trip.domain

import com.ject.global.error.ErrorCode

enum class TripErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    IDEMPOTENCY_KEY_REUSED(409, "이미 사용된 여행 생성 요청입니다. 다시 시도해주세요."),
    INVITATION_NOT_FOUND(404, "초대 코드에 해당하는 여행을 찾을 수 없습니다."),
    ALREADY_CONFIRMED(409, "이미 확정된 여행입니다."),
    CAPACITY_FULL(409, "여행 정원이 모두 찼습니다."),
    NICKNAME_DUPLICATED(409, "이 여행에서 이미 사용 중인 닉네임입니다."),
    CAPACITY_CHANGE_NOT_ALLOWED(409, "총인원을 바꿀 수 없습니다. 확정된 여행이거나 등록된 참여자 수보다 적습니다."),
    ;

    override val code: String = "TRIP_$name"
}
