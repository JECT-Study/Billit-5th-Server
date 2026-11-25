package com.ject.module.trip.presentation.response

import com.ject.module.trip.domain.TripCountryCode
import com.ject.module.trip.domain.TripCurrency
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripStatus
import java.time.LocalDate

/** 명세의 `TripSummary`입니다. [customCountryName]은 [countryCode]가 `ETC`가 아니면 `null`입니다. */
data class TripSummaryResponse(
    val tripId: String,
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
