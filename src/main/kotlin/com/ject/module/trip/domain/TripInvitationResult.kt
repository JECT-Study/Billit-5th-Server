package com.ject.module.trip.domain

/** 관리자가 공유할 초대 코드와 초대 링크입니다. */
data class TripInvitationResult(
    val inviteCode: String,
    val inviteUrl: String,
)
