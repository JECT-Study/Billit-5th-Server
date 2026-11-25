package com.ject.module.trip.domain

import java.time.LocalDate

/**
 * 요청한 회원에게 보여 줄 여행 기본 정보입니다.
 *
 * [registeredMemberCount]는 등록된 로그인 참여자와 비로그인 참여자의 합이고, [myRole]은 요청한 회원의 역할입니다.
 */
data class TripSummaryResult(
    val tripId: Long,
    val name: String,
    val countryCode: TripCountryCode,
    val customCountryName: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val baseCurrency: TripCurrency,
    val memberCapacity: Int,
    val registeredMemberCount: Int,
    val status: TripStatus,
    val myRole: TripMemberRole,
)
