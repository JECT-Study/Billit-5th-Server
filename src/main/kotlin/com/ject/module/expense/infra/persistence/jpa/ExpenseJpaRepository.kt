package com.ject.module.expense.infra.persistence.jpa

import com.ject.module.expense.domain.Expense
import com.ject.module.expense.domain.ExpenseCurrency
import com.ject.module.expense.domain.PaymentMethod
import com.ject.module.expense.domain.RateSource
import com.ject.module.expense.domain.SplitType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface ExpenseJpaRepository : JpaRepository<Expense, Long> {
    fun findByIdempotencyKey(idempotencyKey: UUID): Expense?

    fun existsByTripIdAndDeletedAtIsNull(tripId: Long): Boolean

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

    /** 벌크 UPDATE는 Auditing을 거치지 않으므로 수정 시각도 이 쿼리에서 바꿉니다. */
    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Expense e
        SET e.name = :name,
            e.splitType = :splitType,
            e.originalAmount = :originalAmount,
            e.discountAmount = :discountAmount,
            e.amount = :amount,
            e.currency = :currency,
            e.paidDate = :paidDate,
            e.payerTripMemberId = :payerTripMemberId,
            e.paymentMethod = :paymentMethod,
            e.exchangeRate = :exchangeRate,
            e.exchangeRateSource = :exchangeRateSource,
            e.krwAmount = :krwAmount,
            e.updatedAt = :now
        WHERE e.id = :expenseId AND e.tripId = :tripId AND e.deletedAt IS NULL AND e.settlementId IS NULL
          AND e.editLockOwnerTripMemberId = :ownerTripMemberId AND e.editLockToken = :token AND e.editLockExpiresAt > :now
        """,
    )
    fun updateIfEditLocked(
        @Param("expenseId") expenseId: Long,
        @Param("tripId") tripId: Long,
        @Param("ownerTripMemberId") ownerTripMemberId: Long,
        @Param("token") token: UUID,
        @Param("name") name: String,
        @Param("splitType") splitType: SplitType,
        @Param("originalAmount") originalAmount: Long,
        @Param("discountAmount") discountAmount: Long,
        @Param("amount") amount: Long,
        @Param("currency") currency: ExpenseCurrency,
        @Param("paidDate") paidDate: LocalDate,
        @Param("payerTripMemberId") payerTripMemberId: Long,
        @Param("paymentMethod") paymentMethod: PaymentMethod,
        @Param("exchangeRate") exchangeRate: BigDecimal,
        @Param("exchangeRateSource") exchangeRateSource: RateSource,
        @Param("krwAmount") krwAmount: Long,
        @Param("now") now: Instant,
    ): Int

    /** 행을 지우지 않고 삭제 시각을 기록합니다. 벌크 UPDATE는 Auditing을 거치지 않으므로 수정 시각도 이 쿼리에서 바꿉니다. */
    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Expense e
        SET e.deletedAt = :now, e.updatedAt = :now
        WHERE e.id = :expenseId AND e.tripId = :tripId AND e.deletedAt IS NULL AND e.settlementId IS NULL
          AND (e.editLockExpiresAt IS NULL OR e.editLockExpiresAt <= :now)
        """,
    )
    fun deleteIfUnlocked(
        @Param("expenseId") expenseId: Long,
        @Param("tripId") tripId: Long,
        @Param("now") now: Instant,
    ): Int
}
