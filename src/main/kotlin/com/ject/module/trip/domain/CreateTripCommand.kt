package com.ject.module.trip.domain

import java.time.LocalDate

/**
 * 여행 생성 입력입니다.
 *
 * [name]과 [customCountryName]은 앞뒤 공백을 지운 값입니다. [customCountryName]은 [countryCode]가 [TripCountryCode.ETC]일 때만 있습니다.
 * [memberCapacity]는 생성자를 포함한 총인원이고, [nickname]은 생성자의 여행방 닉네임입니다.
 */
data class CreateTripCommand(
    val name: String,
    val countryCode: TripCountryCode,
    val customCountryName: String?,
    val baseCurrency: TripCurrency,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val memberCapacity: Int,
    val nickname: String,
)
