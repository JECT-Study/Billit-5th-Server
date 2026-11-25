package com.ject.module.trip.domain

/** 여행 저장을 막은 유니크 제약입니다. */
enum class TripCreationConflict {
    /** 같은 여행 생성 고유키의 여행이 이미 저장돼 있습니다. */
    IDEMPOTENCY_KEY_DUPLICATED,

    /** 같은 초대 코드의 여행이 이미 저장돼 있습니다. */
    INVITE_CODE_DUPLICATED,
}
