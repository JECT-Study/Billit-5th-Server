package com.ject.module.trip.implement

import com.ject.module.trip.domain.CreateTripCommand
import com.ject.module.trip.domain.Trip
import com.ject.module.trip.domain.TripCreationConflictException
import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import com.ject.module.trip.domain.TripSummaryResult
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class TripCreator(
    private val tripRepository: TripRepository,
) {
    /**
     * 여행, 여행 생성 고유키와 생성자의 참여 정보를 한 트랜잭션에 저장합니다. 하나라도 실패하면 모두 저장하지 않습니다.
     *
     * - 여행은 확정 전 상태로 만들고, 생성자를 관리자이자 첫 참여자로 등록합니다. 나머지 인원의 빈자리 행은 만들지 않습니다.
     * - 생성자는 초대 코드로 참여하지 않았으므로 참여 시각을 기록하지 않습니다.
     * - 같은 고유키나 초대 코드의 여행이 이미 있으면 [TripCreationConflictException]으로 롤백합니다. 기존 여행 조회와 재시도는 호출하는 쪽이 이 트랜잭션 밖에서 합니다.
     */
    @Transactional
    fun create(
        memberId: Long,
        idempotencyKey: UUID,
        command: CreateTripCommand,
        inviteCode: String,
    ): TripSummaryResult {
        val trip =
            Trip(
                name = command.name,
                startDate = command.startDate,
                endDate = command.endDate,
                countryCode = command.countryCode,
                customCountryName = command.customCountryName,
                baseCurrency = command.baseCurrency,
                memberCapacity = command.memberCapacity,
                status = TripStatus.UNCONFIRMED,
                confirmedAt = null,
                inviteCode = inviteCode,
                idempotencyKey = idempotencyKey,
            )
        val tripId = tripRepository.saveTrip(trip)

        tripRepository.saveTripMember(
            TripMember(
                tripId = tripId,
                memberId = memberId,
                tripNickname = command.tripNickname,
                role = TripMemberRole.ADMIN,
                joinedAt = null,
            ),
        )

        return TripSummaryResult(
            tripId = tripId,
            name = trip.name,
            countryCode = trip.countryCode,
            customCountryName = trip.customCountryName,
            startDate = trip.startDate,
            endDate = trip.endDate,
            baseCurrency = trip.baseCurrency,
            memberCapacity = trip.memberCapacity,
            registeredMemberCount = 1,
            status = trip.status,
            myRole = TripMemberRole.ADMIN,
        )
    }
}
