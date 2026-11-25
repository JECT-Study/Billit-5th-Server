package com.ject.module.trip.domain

import java.time.LocalDate

/**
 * 초대 코드로 찾은 여행의 참여 전 확인 정보입니다.
 *
 * [registeredMemberCount]는 등록된 로그인 참여자와 비로그인 참여자의 합이고, [isAlreadyJoined]는 요청한 회원이 이미 참여 중인지 여부입니다.
 */
data class TripInvitationSearchResult(
    val tripId: Long,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val registeredMemberCount: Int,
    val memberCapacity: Int,
    val status: TripStatus,
    val isAlreadyJoined: Boolean,
)
