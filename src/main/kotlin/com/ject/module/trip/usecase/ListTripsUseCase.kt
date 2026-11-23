package com.ject.module.trip.usecase

import com.ject.module.trip.domain.TripListItemResult
import com.ject.module.trip.domain.TripListResult
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripScheduleStatus
import com.ject.module.trip.domain.TripStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate

/** 요청한 회원이 참여 중인 여행을 페이지 단위로 조회합니다. 나간 여행과 삭제된 여행은 포함하지 않습니다. */
@Service
class ListTripsUseCase(
    private val tripRepository: TripRepository,
) {
    /**
     * [today]는 사용자 기기의 현지 날짜이며, [filter]와 일정 상태는 이 날짜로 나눕니다.
     * 페이지는 필터를 적용한 결과에 나눕니다. 지출 기능 전이므로 요청한 회원의 누적 부담액은 0원입니다.
     * 전체 건수와 페이지 항목이 같은 시점의 데이터를 보도록 두 조회를 `REPEATABLE READ` 읽기 전용 트랜잭션 하나로 묶습니다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(
        memberId: Long,
        filter: TripScheduleStatus?,
        today: LocalDate,
        page: Int,
        size: Int,
    ): TripListResult {
        val offset = page.toLong() * size
        val totalElements = tripRepository.countMyTrips(memberId, filter, today)
        // 조회 시작 위치가 전체 건수 이상이거나 JPA가 받는 Int 범위를 넘으면 꺼낼 여행이 없으므로 조회하지 않습니다.
        val trips =
            if (offset >= totalElements || offset > Int.MAX_VALUE) {
                emptyList()
            } else {
                tripRepository.findMyTrips(memberId, filter, today, offset.toInt(), size)
            }

        return TripListResult(
            items =
                trips.map {
                    TripListResult.Item(
                        tripId = it.tripId,
                        name = it.name,
                        countryCode = it.countryCode,
                        customCountryName = it.customCountryName,
                        startDate = it.startDate,
                        endDate = it.endDate,
                        memberCapacity = it.memberCapacity,
                        registeredMemberCount = Math.toIntExact(it.registeredMemberCount),
                        status = it.status,
                        scheduleStatus = scheduleStatus(it, today),
                        myRole = it.myRole,
                        myShareAmountKrw = BigDecimal.ZERO.setScale(2),
                    )
                },
            totalElements = totalElements,
            hasNext = offset + size < totalElements,
        )
    }

    /** 확정 전 여행은 날짜와 관계없이 초대중이고, 확정 후 여행만 시작일과 종료일(양 끝 포함)로 나눕니다. 목록 필터 조건과 같은 기준입니다. */
    private fun scheduleStatus(
        trip: TripListItemResult,
        today: LocalDate,
    ): TripScheduleStatus =
        when (trip.status) {
            TripStatus.UNCONFIRMED -> {
                TripScheduleStatus.INVITING
            }

            TripStatus.CONFIRMED -> {
                when {
                    today.isBefore(trip.startDate) -> TripScheduleStatus.UPCOMING
                    today.isAfter(trip.endDate) -> TripScheduleStatus.ENDED
                    else -> TripScheduleStatus.ONGOING
                }
            }
        }
}
