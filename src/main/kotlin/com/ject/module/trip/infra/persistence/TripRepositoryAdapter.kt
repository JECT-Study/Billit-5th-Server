package com.ject.module.trip.infra.persistence

import com.ject.global.persistence.violatedUniqueConstraint
import com.ject.module.trip.domain.IdempotentTripResult
import com.ject.module.trip.domain.Trip
import com.ject.module.trip.domain.TripCreationConflict
import com.ject.module.trip.domain.TripCreationConflictException
import com.ject.module.trip.domain.TripListItemResult
import com.ject.module.trip.domain.TripMember
import com.ject.module.trip.domain.TripMemberRole
import com.ject.module.trip.domain.TripMemberStatus
import com.ject.module.trip.domain.TripNicknameDuplicatedException
import com.ject.module.trip.domain.TripParticipantResult
import com.ject.module.trip.domain.TripRepository
import com.ject.module.trip.domain.TripScheduleStatus
import com.ject.module.trip.domain.TripStatus
import com.ject.module.trip.infra.persistence.jdsl.TripJdslRepository
import com.ject.module.trip.infra.persistence.jpa.TripJpaRepository
import com.ject.module.trip.infra.persistence.jpa.TripMemberJpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class TripRepositoryAdapter(
    private val tripJpaRepository: TripJpaRepository,
    private val tripMemberJpaRepository: TripMemberJpaRepository,
    private val tripJdslRepository: TripJdslRepository,
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
        tripJpaRepository.findIdempotentTrip(idempotencyKey, TripMemberRole.ADMIN, TripMemberStatus.REGISTERED)

    override fun findMyTrips(
        memberId: Long,
        filter: TripScheduleStatus?,
        today: LocalDate,
        offset: Int,
        limit: Int,
    ): List<TripListItemResult> = tripJdslRepository.findMyTrips(memberId, filter, today, offset, limit)

    override fun countMyTrips(
        memberId: Long,
        filter: TripScheduleStatus?,
        today: LocalDate,
    ): Long = tripJdslRepository.countMyTrips(memberId, filter, today)

    override fun findTripMembers(tripId: Long): List<TripMember> = tripMemberJpaRepository.findByTripId(tripId)

    override fun findTrip(tripId: Long): Trip? = tripJpaRepository.findByIdAndDeletedAtIsNull(tripId)

    override fun findTripByInviteCode(inviteCode: String): Trip? = tripJpaRepository.findByInviteCodeAndDeletedAtIsNull(inviteCode)

    override fun findTripByInviteCodeForUpdate(inviteCode: String): Trip? =
        tripJpaRepository.findByInviteCodeAndDeletedAtIsNullForUpdate(inviteCode)

    override fun findTripMember(
        tripId: Long,
        memberId: Long,
    ): TripMember? = tripMemberJpaRepository.findByTripIdAndMemberId(tripId, memberId)

    override fun countTripMembers(tripId: Long): Int =
        Math.toIntExact(tripMemberJpaRepository.countByTripIdAndStatusIn(tripId, TripMemberStatus.REGISTERED))

    override fun saveParticipant(tripMember: TripMember): Long =
        try {
            checkNotNull(tripMemberJpaRepository.saveAndFlush(tripMember).id)
        } catch (exception: DataIntegrityViolationException) {
            if (exception.violatedUniqueConstraint() != NICKNAME_UNIQUE_CONSTRAINT) {
                throw exception
            }
            throw TripNicknameDuplicatedException(exception)
        }

    override fun findTripForUpdate(tripId: Long): Trip? = tripJpaRepository.findByIdAndDeletedAtIsNullForUpdate(tripId)

    override fun deleteTripMember(tripMember: TripMember) {
        tripMemberJpaRepository.delete(tripMember)
        tripMemberJpaRepository.flush()
    }

    override fun findTripStatus(tripId: Long): TripStatus? = findTrip(tripId)?.status

    override fun findParticipants(tripId: Long): List<TripParticipantResult> =
        findTripMembers(tripId).map {
            TripParticipantResult(tripMemberId = checkNotNull(it.id), memberId = it.memberId, role = it.role, status = it.status)
        }

    companion object {
        private const val IDEMPOTENCY_KEY_UNIQUE_CONSTRAINT = "uq_trip_idempotency_key"
        private const val INVITE_CODE_UNIQUE_CONSTRAINT = "uq_trip_invite_code"
        private const val NICKNAME_UNIQUE_CONSTRAINT = "uq_trip_member_trip_id_trip_nickname"
    }
}
