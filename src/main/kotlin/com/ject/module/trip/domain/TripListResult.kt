package com.ject.module.trip.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 내 여행 목록의 한 페이지입니다.
 *
 * [totalElements]는 필터 조건에 맞는 전체 여행 수이고, [hasNext]는 다음 페이지가 있는지입니다.
 */
data class TripListResult(
    val items: List<Item>,
    val totalElements: Long,
    val hasNext: Boolean,
) {
    /** [scheduleStatus]는 요청의 오늘 날짜로 계산한 일정 상태이고, [myShareAmountKrw]는 요청한 회원의 원화 누적 부담액입니다. */
    data class Item(
        val tripId: Long,
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
        val myShareAmountKrw: BigDecimal,
    )
}
