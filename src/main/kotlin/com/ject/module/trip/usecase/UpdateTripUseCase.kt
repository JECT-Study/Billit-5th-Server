package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.TripCountryCode
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripStatus
import com.ject.module.trip.domain.TripSummaryResult
import com.ject.module.trip.domain.UpdateTripCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자가 여행 기본 정보와 총인원을 바꿉니다. 보낸 필드만 바꾸고, 저장된 값과 합친 결과를 검증합니다.
 *
 * 여행 행을 잠근 뒤 확정 여부와 등록 인원을 확인하므로, 확정이나 참여와 동시에 요청해도 확정한 여행의 총인원을 바꾸거나 등록 인원보다 줄이지 않습니다.
 * 기존 지출과 정산에는 반영하지 않습니다.
 */
@Service
class UpdateTripUseCase(
    private val tripRepository: TripRepository,
) {
    /**
     * 1. 여행이 없거나 삭제됐으면 404, 요청한 회원이 이 여행의 관리자가 아니면 403입니다.
     * 2. 합친 결과에서 종료일이 시작일보다 빠르거나, 기타 국가인데 국가명이 없거나 기타 국가가 아닌데 국가명을 보냈으면 400입니다.
     *    기타 국가에서 다른 국가로 바꾸면 저장된 국가명을 비웁니다. 대표 국가를 바꿔도 기본 통화는 바꾸지 않습니다.
     * 3. 총인원을 다른 값으로 바꾸는데 확정된 여행이거나 등록 인원보다 적으면 409 `TRIP_CAPACITY_CHANGE_NOT_ALLOWED`입니다. 지금과 같은 값은 바꾸는 요청으로 보지 않습니다.
     */
    @Transactional
    fun update(
        memberId: Long,
        tripId: Long,
        command: UpdateTripCommand,
    ): TripSummaryResult {
        val trip = tripRepository.findTripForUpdate(tripId) ?: throw BusinessException(CommonErrorCode.NOT_FOUND)
        if (tripRepository.findTripMember(tripId, memberId)?.role != TripMemberRole.ADMIN) {
            throw BusinessException(CommonErrorCode.FORBIDDEN)
        }

        val countryCode = command.countryCode ?: trip.countryCode
        val customCountryName =
            when {
                command.customCountryName != null -> command.customCountryName
                countryCode == TripCountryCode.ETC && trip.countryCode == TripCountryCode.ETC -> trip.customCountryName
                else -> null
            }
        val startDate = command.startDate ?: trip.startDate
        val endDate = command.endDate ?: trip.endDate
        if (endDate.isBefore(startDate) || (customCountryName != null) != (countryCode == TripCountryCode.ETC)) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }

        val registeredMemberCount = tripRepository.countTripMembers(tripId)
        val memberCapacity = command.memberCapacity ?: trip.memberCapacity
        if (memberCapacity != trip.memberCapacity && (trip.status == TripStatus.CONFIRMED || memberCapacity < registeredMemberCount)) {
            throw BusinessException(TripErrorCode.CAPACITY_CHANGE_NOT_ALLOWED)
        }

        trip.update(
            name = command.name ?: trip.name,
            countryCode = countryCode,
            customCountryName = customCountryName,
            baseCurrency = command.baseCurrency ?: trip.baseCurrency,
            startDate = startDate,
            endDate = endDate,
            memberCapacity = memberCapacity,
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
            registeredMemberCount = registeredMemberCount,
            status = trip.status,
            myRole = TripMemberRole.ADMIN,
        )
    }
}
