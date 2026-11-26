package com.ject.module.expense.infra.persistence.jpa

import com.ject.module.expense.domain.Expense
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ExpenseJpaRepository : JpaRepository<Expense, Long> {
    fun findByIdempotencyKey(idempotencyKey: UUID): Expense?

    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Expense e
        SET e.editLockOwnerTripMemberId = :ownerTripMemberId,
            e.editLockToken = :token,
            e.editLockExpiresAt = :expiresAt
        WHERE e.id = :expenseId AND e.tripId = :tripId AND e.deletedAt IS NULL AND e.settlementId IS NULL
          AND (e.editLockExpiresAt IS NULL OR e.editLockExpiresAt <= :now)
        """,
    )
    fun acquireEditLock(
        @Param("expenseId") expenseId: Long,
        @Param("tripId") tripId: Long,
        @Param("ownerTripMemberId") ownerTripMemberId: Long,
        @Param("token") token: UUID,
        @Param("expiresAt") expiresAt: Instant,
        @Param("now") now: Instant,
    ): Int

    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE expense
        SET edit_lock_expires_at = edit_lock_expires_at + (:extensionSeconds * interval '1 second')
        WHERE id = :expenseId AND trip_id = :tripId AND deleted_at IS NULL AND settlement_id IS NULL
          AND edit_lock_owner_trip_member_id = :ownerTripMemberId AND edit_lock_token = :token AND edit_lock_expires_at > :now
        """,
        nativeQuery = true,
    )
    fun extendEditLock(
        @Param("expenseId") expenseId: Long,
        @Param("tripId") tripId: Long,
        @Param("ownerTripMemberId") ownerTripMemberId: Long,
        @Param("token") token: UUID,
        @Param("extensionSeconds") extensionSeconds: Long,
        @Param("now") now: Instant,
    ): Int

    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Expense e
        SET e.editLockOwnerTripMemberId = NULL, e.editLockToken = NULL, e.editLockExpiresAt = NULL
        WHERE e.id = :expenseId AND e.tripId = :tripId
          AND e.editLockOwnerTripMemberId = :ownerTripMemberId AND e.editLockToken = :token
        """,
    )
    fun releaseEditLock(
        @Param("expenseId") expenseId: Long,
        @Param("tripId") tripId: Long,
        @Param("ownerTripMemberId") ownerTripMemberId: Long,
        @Param("token") token: UUID,
    ): Int
}
