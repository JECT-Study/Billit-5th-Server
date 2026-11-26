package com.ject.module.trip.domain

/** 다른 업무가 여행 참여 여부와 관리자 여부를 판단할 때 쓰는 여행 참여자 값입니다. [memberId]는 비로그인 참여자이면 `null`입니다. */
data class TripParticipantResult(
    val tripMemberId: Long,
    val memberId: Long?,
    val role: TripMemberRole,
    val status: TripMemberStatus,
)
