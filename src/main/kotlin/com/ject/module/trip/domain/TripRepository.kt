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

    /** 여행의 모든 참여자 행을 조회합니다. 나간 회원과 탈퇴 회원도 포함하며 순서는 보장하지 않습니다. */
    fun findTripMembers(tripId: Long): List<TripMember>

    /** 삭제되지 않은 여행을 조회합니다. 없거나 삭제된 여행이면 `null`입니다. */
    fun findTrip(tripId: Long): Trip?

    /** 초대 코드가 정확히 같은 여행 중 삭제되지 않은 여행을 조회합니다. 없거나 삭제된 여행이면 `null`입니다. */
    fun findTripByInviteCode(inviteCode: String): Trip?

    /**
     * [findTripByInviteCode]와 같은 여행을 조회하면서 여행 행에 쓰기 잠금을 겁니다.
     *
     * 참여자 수와 확정 여부를 확인한 뒤 참여자를 저장하는 동안 다른 참여 요청이 같은 여행을 바꾸지 못하게 합니다.
     * 트랜잭션: 호출하는 쪽의 트랜잭션이 필요하며, 잠금은 그 트랜잭션이 끝날 때 풀립니다.
     */
    fun findTripByInviteCodeForUpdate(inviteCode: String): Trip?

    /** 회원의 여행 참여 정보를 조회합니다. 나간 회원의 참여 정보도 반환하며, 참여한 적이 없으면 `null`입니다. */
    fun findTripMember(
        tripId: Long,
        memberId: Long,
    ): TripMember?

    /** 여행에 등록된 로그인 참여자(`MEMBER`)와 비로그인 참여자(`GUEST`)의 수입니다. 나간 회원과 탈퇴 회원은 세지 않습니다. */
    fun countTripMembers(tripId: Long): Int

    /**
     * 초대 코드로 참여한 회원이나 비로그인 참여자를 저장하고 여행 참여자 ID를 반환합니다. 이미 저장한 참여자의 여행방 닉네임을 바꿀 때도 씁니다.
     *
     * - 같은 여행에 같은 여행방 닉네임이 있어 `uq_trip_member_trip_id_trip_nickname` 위반이면 [TripNicknameDuplicatedException]을 던집니다. 다른 DB 오류는 그대로 던집니다.
     * - 트랜잭션: 호출하는 쪽의 트랜잭션에 참여합니다. 제약 위반이 난 트랜잭션은 더 쓸 수 없으므로 예외를 트랜잭션 밖으로 전파해 롤백합니다.
     */
    fun saveParticipant(tripMember: TripMember): Long

    /**
     * [findTrip]과 같은 여행을 조회하면서 여행 행에 쓰기 잠금을 겁니다.
     *
     * 확정 여부와 등록 인원을 확인한 뒤 참여자 구성이나 여행을 바꾸는 동안 같은 여행의 참여, 확정과 인원 변경 요청이 끼어들지 못하게 합니다.
     * 트랜잭션: 호출하는 쪽의 트랜잭션이 필요하며, 잠금은 그 트랜잭션이 끝날 때 풀립니다.
     */
    fun findTripForUpdate(tripId: Long): Trip?

    /** 여행 참여자 행을 지웁니다. 비로그인 참여자 삭제에만 씁니다. 트랜잭션은 호출하는 쪽이 엽니다. */
    fun deleteTripMember(tripMember: TripMember)

    /** 삭제되지 않은 여행의 확정 상태입니다. 없거나 삭제된 여행이면 `null`입니다. 다른 업무가 여행을 확인할 때 씁니다. */
    fun findTripStatus(tripId: Long): TripStatus?

    /** 여행의 모든 참여자를 값으로 조회합니다. 나간 회원과 탈퇴 회원도 포함하며 순서는 보장하지 않습니다. 다른 업무가 참여 여부를 판단할 때 씁니다. */
    fun findParticipants(tripId: Long): List<TripParticipantResult>
}
