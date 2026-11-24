package com.ject.module.trip.infra.persistence.jpa

import com.ject.module.trip.domain.IdempotentTripResult
import com.ject.module.trip.domain.Trip
import com.ject.module.trip.domain.TripMemberRole
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TripJpaRepository : JpaRepository<Trip, Long> {
    /** 삭제된 여행도 조회합니다. 여행당 관리자는 한 명이므로 여행 하나에 한 행만 나옵니다. */
    @Query(
        """
        SELECT new com.ject.module.trip.domain.IdempotentTripResult(
            t.id,
            t.name,
            t.countryCode,
            t.customCountryName,
            t.startDate,
            t.endDate,
            t.baseCurrency,
            t.memberCapacity,
            t.status,
            t.deletedAt,
            a.memberId,
            (SELECT count(m) FROM TripMember m WHERE m.tripId = t.id)
        )
        FROM Trip t
        LEFT JOIN TripMember a ON a.tripId = t.id AND a.role = :adminRole
        WHERE t.idempotencyKey = :idempotencyKey
        """,
    )
    fun findIdempotentTrip(
        @Param("idempotencyKey") idempotencyKey: UUID,
        @Param("adminRole") adminRole: TripMemberRole,
    ): IdempotentTripResult?

    /** 초대 코드가 같고 삭제되지 않은 여행 행을 `SELECT ... FOR UPDATE` 계열 잠금으로 조회합니다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.inviteCode = :inviteCode AND t.deletedAt IS NULL")
    fun findByInviteCodeAndDeletedAtIsNullForUpdate(
        @Param("inviteCode") inviteCode: String,
    ): Trip?
}
