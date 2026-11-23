package com.ject.module.trip.domain

import java.time.Instant
import java.time.LocalDate

/**
 * 여행 생성 고유키로 찾은 기존 여행의 현재 정보입니다. 삭제된 여행도 포함합니다.
 *
 * [creatorMemberId]는 그 키로 여행을 만든 회원(관리자)이며, 관리자 참여 정보가 없으면 `null`입니다.
 * [deletedAt]이 `null`이 아니면 삭제된 여행입니다. [registeredMemberCount]는 등록된 참여자 수입니다.
 */
data class IdempotentTripResult(
    val tripId: Long,
    val name: String,
    val countryCode: TripCountryCode,
    val customCountryName: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val baseCurrency: TripCurrency,
    val memberCapacity: Int,
    val status: TripStatus,
    val deletedAt: Instant?,
    val creatorMemberId: Long?,
    val registeredMemberCount: Long,
)
