package com.ject.module.expense.domain

import com.ject.global.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "expense")
class Expense(
    @field:Column(name = "trip_id", nullable = false)
    val tripId: Long,
    @field:Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: UUID,
    @field:Column(name = "name", nullable = false)
    val name: String,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "split_type", nullable = false)
    val splitType: SplitType,
    @field:Column(name = "original_amount", nullable = false)
    val originalAmount: Long,
    @field:Column(name = "discount_amount", nullable = false)
    val discountAmount: Long,
    @field:Column(name = "amount", nullable = false)
    val amount: Long,
    @field:JdbcTypeCode(SqlTypes.CHAR)
    @field:Column(name = "currency", nullable = false)
    val currency: String,
    @field:Column(name = "paid_date", nullable = false)
    val paidDate: LocalDate,
    @field:Column(name = "payer_trip_member_id", nullable = false)
    val payerTripMemberId: Long,
    @field:Column(name = "created_by_trip_member_id", nullable = false)
    val createdByTripMemberId: Long,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "payment_method", nullable = false)
    val paymentMethod: PaymentMethod,
    @field:Column(name = "exchange_rate", nullable = false)
    val exchangeRate: BigDecimal,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "exchange_rate_source", nullable = false)
    val exchangeRateSource: RateSource,
    @field:Column(name = "exchange_rate_provider", nullable = true)
    val exchangeRateProvider: String?,
    @field:Column(name = "krw_amount", nullable = false)
    val krwAmount: Long,
    @field:Column(name = "edit_lock_owner_trip_member_id", nullable = true)
    val editLockOwnerTripMemberId: Long?,
    @field:Column(name = "edit_lock_expires_at", nullable = true)
    val editLockExpiresAt: Instant?,
    @field:Column(name = "edit_lock_token", nullable = true)
    val editLockToken: UUID?,
    @field:Column(name = "settlement_id", nullable = true)
    val settlementId: Long?,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set

    @field:Column(name = "deleted_at")
    var deletedAt: Instant? = null
        protected set

    fun isDeleted(): Boolean = deletedAt != null
}
