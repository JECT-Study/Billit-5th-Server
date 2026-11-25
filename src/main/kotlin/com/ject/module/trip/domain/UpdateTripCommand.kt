package com.ject.module.trip.domain

import java.time.LocalDate

/** 여행 정보 수정 요청입니다. `null`인 필드는 요청에 없던 필드이며 저장된 값을 유지합니다. 문자열은 앞뒤 공백을 지운 값입니다. */
data class UpdateTripCommand(
    val name: String?,
    val countryCode: TripCountryCode?,
    val customCountryName: String?,
    val baseCurrency: TripCurrency?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val memberCapacity: Int?,
)
