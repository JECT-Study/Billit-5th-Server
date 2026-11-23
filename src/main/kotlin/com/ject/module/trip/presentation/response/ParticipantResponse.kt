package com.ject.module.trip.presentation.response

import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus

/** 명세의 `Participant`입니다. [memberId]는 비로그인 참여자와 탈퇴 회원이면 `null`이고, [shareAmountKrw]는 소수점 이하 2자리 원화 금액 문자열입니다. */
data class ParticipantResponse(
    val tripMemberId: String,
    val memberId: String?,
    val displayName: String,
    val role: TripMemberRole,
    val participantStatus: TripMemberStatus,
    val shareAmountKrw: String,
)
