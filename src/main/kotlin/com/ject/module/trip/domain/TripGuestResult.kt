package com.ject.module.trip.domain

/** 등록하거나 이름을 바꾼 비로그인 참여자입니다. [tripNickname]은 관리자가 입력한 이름입니다. */
data class TripGuestResult(
    val tripMemberId: Long,
    val tripNickname: String,
)
