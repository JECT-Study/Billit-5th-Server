package com.ject.module.trip.presentation.response

import com.ject.module.trip.domain.TripCountryCode
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripScheduleStatus
import com.ject.module.trip.domain.TripStatus
import java.time.LocalDate

/** 명세의 `TripListItem`입니다. [myShareAmountKrw]는 소수점 이하 2자리 원화 금액 문자열입니다. */
data class TripListItemResponse(
    val tripId: String,
    val name: String,
    val countryCode: TripCountryCode,
    val customCountryName: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val memberCapacity: Int,
    val registeredMemberCount: Int,
    val status: TripStatus,
    val scheduleStatus: TripScheduleStatus,
    val myRole: TripMemberRole,
    val myShareAmountKrw: String,
)
