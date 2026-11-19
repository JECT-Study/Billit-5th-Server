package com.ject.module.trip.domain

/**
 * 초대 코드로 참여한 결과입니다.
 *
 * [isNewMember]는 이번 요청으로 새로 참여했으면 `true`, 이미 참여 중이어서 기존 참여 정보를 돌려줬으면 `false`입니다.
 */
data class TripMembershipResult(
    val tripId: Long,
    val tripMemberId: Long,
    val isNewMember: Boolean,
)
