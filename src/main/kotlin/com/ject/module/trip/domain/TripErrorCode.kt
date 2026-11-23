package com.ject.module.trip.domain

import com.ject.global.error.ErrorCode

enum class TripErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    IDEMPOTENCY_KEY_REUSED(409, "이미 사용된 여행 생성 요청입니다. 다시 시도해주세요."),
    ;

    override val code: String = "TRIP_$name"
}
