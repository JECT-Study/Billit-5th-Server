package com.ject.module.trip.domain

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 여행 상세에 보여 줄 기본 정보와 참여자 목록입니다.
 *
 * [registeredMemberCount]는 참여 중인 로그인 참여자와 비로그인 참여자의 합이고, [myRole]은 요청한 회원의 역할입니다.
 * [participants]는 관리자, 로그인 참여자, 비로그인 참여자, 나간 회원, 탈퇴 회원 순입니다.
 */
data class TripDetailResult(
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
    val participants: List<Participant>,
) {
    /** [memberId]는 비로그인 참여자와 탈퇴 회원이면 `null`이고, [tripNickname]은 탈퇴 회원이면 '탈퇴한 사용자 N'입니다. */
    data class Participant(
        val tripMemberId: Long,
        val memberId: Long?,
        val tripNickname: String,
        val role: TripMemberRole,
        val participantStatus: TripMemberStatus,
        val shareAmountKrw: BigDecimal,
    )
}
