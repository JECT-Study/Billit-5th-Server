package com.ject.module.trip.infra.persistence

import com.ject.global.persistence.violatedUniqueConstraint
import com.ject.module.trip.domain.IdempotentTripResult
import com.ject.module.trip.domain.Trip
import com.ject.module.trip.domain.TripCreationConflict
import com.ject.module.trip.domain.TripCreationConflictException
import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripNicknameDuplicatedException
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.infra.persistence.jpa.TripJpaRepository
import com.ject.module.trip.infra.persistence.jpa.TripMemberJpaRepository
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
            val conflict =
                when (exception.violatedUniqueConstraint()) {
                    IDEMPOTENCY_KEY_UNIQUE_CONSTRAINT -> TripCreationConflict.IDEMPOTENCY_KEY_DUPLICATED
                    INVITE_CODE_UNIQUE_CONSTRAINT -> TripCreationConflict.INVITE_CODE_DUPLICATED
                    else -> throw exception
                }
            throw TripCreationConflictException(conflict, exception)
        }

    override fun saveTripMember(tripMember: TripMember) {
        tripMemberJpaRepository.saveAndFlush(tripMember)
    }

    override fun findByIdempotencyKey(idempotencyKey: UUID): IdempotentTripResult? =
        tripJpaRepository.findIdempotentTrip(idempotencyKey, TripMemberRole.ADMIN)

    override fun findTrip(tripId: Long): Trip? = tripJpaRepository.findByIdAndDeletedAtIsNull(tripId)

    override fun findTripByInviteCode(inviteCode: String): Trip? = tripJpaRepository.findByInviteCodeAndDeletedAtIsNull(inviteCode)

    override fun findTripByInviteCodeForUpdate(inviteCode: String): Trip? =
        tripJpaRepository.findByInviteCodeAndDeletedAtIsNullForUpdate(inviteCode)

    override fun findTripMember(
        tripId: Long,
        memberId: Long,
    ): TripMember? = tripMemberJpaRepository.findByTripIdAndMemberId(tripId, memberId)

    override fun countTripMembers(tripId: Long): Int = Math.toIntExact(tripMemberJpaRepository.countByTripId(tripId))

    override fun saveJoinedTripMember(tripMember: TripMember): Long =
        try {
            checkNotNull(tripMemberJpaRepository.saveAndFlush(tripMember).id)
        } catch (exception: DataIntegrityViolationException) {
            if (exception.violatedUniqueConstraint() != NICKNAME_UNIQUE_CONSTRAINT) {
                throw exception
            }
            throw TripNicknameDuplicatedException(exception)
        }

    companion object {
        private const val IDEMPOTENCY_KEY_UNIQUE_CONSTRAINT = "uq_trip_idempotency_key"
        private const val INVITE_CODE_UNIQUE_CONSTRAINT = "uq_trip_invite_code"
        private const val NICKNAME_UNIQUE_CONSTRAINT = "uq_trip_member_trip_id_trip_nickname"
    }
}
