package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.member.domain.MemberRepository
import com.ject.module.trip.domain.TripDetailResult
import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

/**
 * 여행 기본 정보와 참여자 목록을 조회합니다. 참여 중인 회원만 조회할 수 있으며 여행 종료일이 지나도 조회할 수 있습니다.
 *
 * 조회만 하므로 트랜잭션을 열지 않습니다. 탈퇴 회원의 탈퇴 시각은 회원 업무의 Repository에서 값으로 받습니다.
 */
@Service
class GetTripDetailUseCase(
    private val tripRepository: TripRepository,
    private val memberRepository: MemberRepository,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404입니다.
     * 2. 요청한 회원이 이 여행에 참여 중(`MEMBER`)이 아니면 403입니다. 나간 회원도 403입니다.
     * 3. 참여자는 관리자, 로그인 참여자(참여한 순서), 비로그인 참여자(추가한 순서), 나간 회원(나간 순서), 탈퇴 회원(탈퇴한 순서) 순으로 둡니다.
     *    탈퇴 회원은 회원 ID를 숨기고 그 순서대로 '탈퇴한 사용자 N'으로 부릅니다. 지출 기능 전이므로 부담액은 0원입니다.
     */
    fun get(
        memberId: Long,
        tripId: Long,
    ): TripDetailResult {
        val trip = tripRepository.findByIdAndDeletedAtIsNull(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        val tripMembers = tripRepository.findTripMembers(tripId)
        val me =
            tripMembers.firstOrNull { it.memberId == memberId && it.status == TripMemberStatus.MEMBER }
                ?: throw BusinessException(CommonErrorCode.FORBIDDEN)

        val withdrawnMemberIds = tripMembers.filter { it.status == TripMemberStatus.WITHDRAWN }.mapNotNull { it.memberId }
        val withdrawnAts = memberRepository.findWithdrawnMembers(withdrawnMemberIds).associate { it.memberId to it.withdrawnAt }
        val orderedMembers =
            tripMembers.sortedWith(
                compareBy<TripMember> { order(it) }
                    .thenBy(nullsLast()) { orderedAt(it, withdrawnAts) }
                    .thenBy { it.id },
            )

        val participants = mutableListOf<TripDetailResult.Participant>()
        var withdrawnNumber = 0
        for (tripMember in orderedMembers) {
            val isWithdrawn = tripMember.status == TripMemberStatus.WITHDRAWN
            if (isWithdrawn) {
                withdrawnNumber++
            }
            val tripNickname = if (isWithdrawn) "$WITHDRAWN_NICKNAME_PREFIX$withdrawnNumber" else checkNotNull(tripMember.tripNickname)
            participants.add(
                TripDetailResult.Participant(
                    tripMemberId = checkNotNull(tripMember.id),
                    memberId = if (isWithdrawn) null else tripMember.memberId,
                    tripNickname = tripNickname,
                    role = tripMember.role,
                    participantStatus = tripMember.status,
                    shareAmountKrw = BigDecimal.ZERO.setScale(2),
                ),
            )
        }
        return TripDetailResult(
            tripId = checkNotNull(trip.id),
            name = trip.name,
            countryCode = trip.countryCode,
            customCountryName = trip.customCountryName,
            startDate = trip.startDate,
            endDate = trip.endDate,
            baseCurrency = trip.baseCurrency,
            memberCapacity = trip.memberCapacity,
            registeredMemberCount = tripMembers.count { it.status in TripMemberStatus.REGISTERED },
            status = trip.status,
            myRole = me.role,
            participants = participants,
        )
    }

    /** 참여 중인 관리자를 맨 앞에 둡니다. 나간 관리자나 탈퇴한 관리자는 상태의 순서를 따라 탈퇴 번호가 탈퇴 순서와 어긋나지 않게 합니다. */
    private fun order(tripMember: TripMember): Int {
        if (tripMember.role == TripMemberRole.ADMIN && tripMember.status == TripMemberStatus.MEMBER) {
            return 0
        }
        return when (tripMember.status) {
            TripMemberStatus.MEMBER -> 1
            TripMemberStatus.GUEST -> 2
            TripMemberStatus.LEFT -> 3
            TripMemberStatus.WITHDRAWN -> 4
        }
    }

    /** 같은 순서 안에서 비교할 시각입니다. 시각이 없는 행은 같은 순서의 맨 뒤에 두고 참여자 ID로 정렬합니다. */
    private fun orderedAt(
        tripMember: TripMember,
        withdrawnAts: Map<Long, Instant>,
    ): Instant? =
        when (tripMember.status) {
            TripMemberStatus.MEMBER -> tripMember.joinedAt
            TripMemberStatus.GUEST -> tripMember.createdAt
            TripMemberStatus.LEFT -> tripMember.leftAt
            TripMemberStatus.WITHDRAWN -> tripMember.memberId?.let { withdrawnAts[it] }
        }

    companion object {
        private const val WITHDRAWN_NICKNAME_PREFIX = "탈퇴한 사용자 "
    }
}
