package com.ject.module.trip.domain

import java.time.LocalDate

/**
 * 요청한 회원이 참여 중인 여행 목록의 한 행입니다.
 *
 * [registeredMemberCount]는 참여 중인 로그인 참여자와 비로그인 참여자의 합이고, [myRole]은 요청한 회원의 역할입니다.
 */
data class TripListItemResult(
    val tripId: Long,
    val name: String,
    val countryCode: TripCountryCode,
    val customCountryName: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val memberCapacity: Int,
    val registeredMemberCount: Long,
    val status: TripStatus,
    val myRole: TripMemberRole,
)
