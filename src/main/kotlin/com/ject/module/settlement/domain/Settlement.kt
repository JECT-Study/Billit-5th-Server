package com.ject.module.settlement.domain

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
import java.time.Instant

@Entity
@Table(name = "settlement")
class Settlement(
    @field:Column(name = "trip_id", nullable = false)
    val tripId: Long,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "settlement_method", nullable = false)
    val settlementMethod: SettlementMethod,
    @field:JdbcTypeCode(SqlTypes.CHAR)
    @field:Column(name = "settlement_currency", nullable = true)
    val settlementCurrency: String?,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "status", nullable = false)
    val status: SettlementStatus,
    @field:Column(name = "preparation_lock_expires_at", nullable = true)
    val preparationLockExpiresAt: Instant?,
    @field:Column(name = "issued_at", nullable = true)
    val issuedAt: Instant?,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
