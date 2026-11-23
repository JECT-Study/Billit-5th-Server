package com.ject.module.trip.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.trip.domain.CreateTripCommand
import com.ject.module.trip.domain.TripCountryCode
import com.ject.module.trip.domain.TripCreationConflict
import com.ject.module.trip.domain.TripCreationConflictException
import com.ject.module.trip.domain.TripErrorCode
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripSummaryResult
import com.ject.module.trip.implement.TripCreator
import com.ject.module.trip.implement.TripInviteCodeGenerator
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 여행을 만들고 생성자를 관리자이자 첫 참여자로 등록합니다. 같은 회원이 같은 고유키로 다시 요청하면 새로 만들지 않고 처음 만든 여행을 반환합니다.
 *
 * 이 UseCase는 전체를 묶는 트랜잭션을 열지 않습니다. 같은 고유키의 요청이 동시에 오면 늦은 요청은 유니크 제약 위반으로 실패하는데,
 * PostgreSQL은 그 트랜잭션에서 더 조회할 수 없습니다. 그래서 저장만 [TripCreator]의 트랜잭션으로 묶고, 위반이 나면 그 트랜잭션이 롤백된 뒤 먼저 저장된 여행을 다시 조회합니다.
 */
@Service
class CreateTripUseCase(
    private val tripRepository: TripRepository,
    private val tripCreator: TripCreator,
    private val tripInviteCodeGenerator: TripInviteCodeGenerator,
) {
    /**
     * 1. 종료일이 시작일보다 빠르거나, 기타 국가인데 국가명이 없거나 기타 국가가 아닌데 국가명이 있으면 400입니다.
     * 2. 고유키로 만든 여행이 이미 있으면 저장하지 않습니다. 요청한 회원이 만든 여행이면 그 여행의 현재 정보를 반환하고, 다른 회원이 만들었거나 삭제된 여행이면 409입니다. 요청 내용은 처음 요청과 비교하지 않습니다.
     * 3. 여행, 고유키와 생성자 참여 정보를 한 트랜잭션에 저장합니다.
     * 4. 저장이 고유키 중복으로 실패하면 같은 키의 요청이 먼저 저장된 것이므로 2번처럼 처리합니다.
     * 5. 저장이 초대 코드 중복으로 실패하면 새 초대 코드로 3번부터 다시 합니다.
     */
    fun create(
        memberId: Long,
        idempotencyKey: UUID,
        command: CreateTripCommand,
    ): TripSummaryResult {
        validate(command)

        val createdTrip = findCreatedTrip(memberId, idempotencyKey)
        if (createdTrip != null) {
            return createdTrip
        }

        for (attempt in 1..MAX_INVITE_CODE_ATTEMPTS) {
            try {
                return tripCreator.create(memberId, idempotencyKey, command, tripInviteCodeGenerator.generate())
            } catch (exception: TripCreationConflictException) {
                when (exception.conflict) {
                    TripCreationConflict.IDEMPOTENCY_KEY_DUPLICATED -> {
                        // 위반 뒤 조회했는데 여행이 없으면 그 사이 지워진 것이므로 삭제된 여행의 키와 같이 거절합니다.
                        return findCreatedTrip(memberId, idempotencyKey)
                            ?: throw BusinessException(TripErrorCode.IDEMPOTENCY_KEY_REUSED, exception)
                    }

                    TripCreationConflict.INVITE_CODE_DUPLICATED -> {
                        continue
                    }
                }
            }
        }
        error("초대 코드가 ${MAX_INVITE_CODE_ATTEMPTS}번 연속으로 겹쳐 여행을 저장하지 못했습니다.")
    }

    private fun validate(command: CreateTripCommand) {
        val hasCustomCountryName = command.customCountryName != null
        val isValid =
            !command.endDate.isBefore(command.startDate) &&
                hasCustomCountryName == (command.countryCode == TripCountryCode.ETC)
        if (!isValid) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }
    }

    /** 고유키로 만든 여행이 없으면 `null`을 반환합니다. 다른 회원이 만들었거나 삭제된 여행이면 기존 여행 정보를 주지 않고 409로 거절합니다. */
    private fun findCreatedTrip(
        memberId: Long,
        idempotencyKey: UUID,
    ): TripSummaryResult? {
        val trip = tripRepository.findByIdempotencyKey(idempotencyKey) ?: return null
        if (trip.deletedAt != null || trip.creatorMemberId != memberId) {
            throw BusinessException(TripErrorCode.IDEMPOTENCY_KEY_REUSED)
        }
        return TripSummaryResult(
            tripId = trip.tripId,
            name = trip.name,
            countryCode = trip.countryCode,
            customCountryName = trip.customCountryName,
            startDate = trip.startDate,
            endDate = trip.endDate,
            baseCurrency = trip.baseCurrency,
            memberCapacity = trip.memberCapacity,
            registeredMemberCount = Math.toIntExact(trip.registeredMemberCount),
            status = trip.status,
            myRole = TripMemberRole.ADMIN,
        )
    }

    companion object {
        /** 초대 코드는 36의 6제곱(약 21억) 가지라 연속으로 겹칠 가능성이 매우 낮습니다. 계속 겹치면 생성기나 DB 문제로 보고 실패합니다. */
        private const val MAX_INVITE_CODE_ATTEMPTS = 5
    }
}
