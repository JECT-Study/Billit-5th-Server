package com.ject.module.trip.infra.persistence

import com.ject.module.trip.domain.IdempotentTripResult
import com.ject.module.trip.domain.Trip
import com.ject.module.trip.domain.TripCreationConflict
import com.ject.module.trip.domain.TripCreationConflictException
import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.infra.persistence.jpa.TripJpaRepository
import com.ject.module.trip.infra.persistence.jpa.TripMemberJpaRepository
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TripRepositoryAdapter(
    private val tripJpaRepository: TripJpaRepository,
    private val tripMemberJpaRepository: TripMemberJpaRepository,
) : TripRepository {
    override fun saveTrip(trip: Trip): Long =
        try {
            checkNotNull(tripJpaRepository.saveAndFlush(trip).id)
        } catch (exception: DataIntegrityViolationException) {
            val conflict = findCreationConflict(exception) ?: throw exception
            throw TripCreationConflictException(conflict, exception)
        }

    override fun saveTripMember(tripMember: TripMember) {
        tripMemberJpaRepository.saveAndFlush(tripMember)
    }

    override fun findByIdempotencyKey(idempotencyKey: UUID): IdempotentTripResult? =
        tripJpaRepository.findIdempotentTrip(idempotencyKey, TripMemberRole.ADMIN, TripMemberStatus.REGISTERED)

    /** 고유키나 초대 코드의 유니크 제약 위반이면 해당 충돌을, 다른 무결성 오류이면 `null`을 반환합니다. */
    private fun findCreationConflict(exception: DataIntegrityViolationException): TripCreationConflict? {
        val violation =
            generateSequence(exception.cause) { it.cause }
                .filterIsInstance<ConstraintViolationException>()
                .firstOrNull() ?: return null
        if (violation.sqlState != UNIQUE_VIOLATION_SQL_STATE) {
            return null
        }
        val constraintName = violation.constraintName
        return when {
            constraintName.equals(IDEMPOTENCY_KEY_UNIQUE_CONSTRAINT, ignoreCase = true) -> TripCreationConflict.IDEMPOTENCY_KEY_DUPLICATED
            constraintName.equals(INVITE_CODE_UNIQUE_CONSTRAINT, ignoreCase = true) -> TripCreationConflict.INVITE_CODE_DUPLICATED
            else -> null
        }
    }

    companion object {
        private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        private const val IDEMPOTENCY_KEY_UNIQUE_CONSTRAINT = "uq_trip_idempotency_key"
        private const val INVITE_CODE_UNIQUE_CONSTRAINT = "uq_trip_invite_code"
    }
}
