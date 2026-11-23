package com.ject.module.trip.domain

import java.time.LocalDate
import java.util.UUID

interface TripRepository {
    /**
     * 새 여행을 저장하고 여행 ID를 반환합니다.
     *
     * - 같은 여행 생성 고유키나 초대 코드의 여행이 이미 저장돼 있으면 `uq_trip_idempotency_key`, `uq_trip_invite_code` 위반으로 판단해 [TripCreationConflictException]을 던집니다. 다른 DB 오류는 그대로 던집니다.
     * - 트랜잭션: 호출하는 쪽의 트랜잭션에 참여합니다. 제약 위반이 난 트랜잭션은 더 쓸 수 없으므로 예외를 트랜잭션 밖으로 전파해 롤백합니다.
     */
    fun saveTrip(trip: Trip): Long

    /** 여행 참여자를 저장합니다. DB 오류는 그대로 던집니다. 트랜잭션은 호출하는 쪽이 엽니다. */
    fun saveTripMember(tripMember: TripMember)

    /**
     * 여행 생성 고유키로 만든 여행의 현재 정보를 조회합니다. 삭제된 여행도 반환하며, 그 키로 만든 여행이 없으면 `null`입니다.
     *
     * 영속성 컨텍스트에 남은 값이 아니라 조회 시점의 DB 값을 반환합니다.
     */
    fun findByIdempotencyKey(idempotencyKey: UUID): IdempotentTripResult?

    /**
     * 회원이 참여 중(`MEMBER`)인 삭제되지 않은 여행을 만든 시각의 역순, 같으면 여행 ID의 역순으로 [offset]부터 [limit]개 조회합니다.
     *
     * [filter]가 있으면 [today]를 기준으로 그 일정 상태의 여행만 조회하고, 필터를 적용한 결과에 페이지를 나눕니다. `null`이면 일정 상태로 제한하지 않습니다.
     */
    fun findMyTrips(
        memberId: Long,
        filter: TripScheduleStatus?,
        today: LocalDate,
        offset: Int,
        limit: Int,
    ): List<TripListItemResult>

    /** [findMyTrips]와 같은 조건에 맞는 전체 여행 수를 조회합니다. */
    fun countMyTrips(
        memberId: Long,
        filter: TripScheduleStatus?,
        today: LocalDate,
    ): Long

    /** 삭제되지 않은 여행을 조회합니다. 없거나 삭제됐으면 `null`입니다. */
    fun findByIdAndDeletedAtIsNull(tripId: Long): Trip?

    /** 여행의 모든 참여자 행을 조회합니다. 나간 회원과 탈퇴 회원도 포함하며 순서는 보장하지 않습니다. */
    fun findTripMembers(tripId: Long): List<TripMember>
}
