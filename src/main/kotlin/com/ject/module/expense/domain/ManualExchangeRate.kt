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
import java.time.LocalDate

@Entity
@Table(name = "manual_exchange_rate")
class ManualExchangeRate(
    @field:Column(name = "trip_id", nullable = false)
    val tripId: Long,
    @field:JdbcTypeCode(SqlTypes.CHAR)
    @field:Column(name = "currency", nullable = false)
    val currency: String,
    @field:Column(name = "rate", nullable = false)
    val rate: BigDecimal,
    @field:Column(name = "exchange_date", nullable = false)
    val exchangeDate: LocalDate,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "exchange_method", nullable = false)
    val exchangeMethod: ExchangeMethod,
    @field:Column(name = "created_by_trip_member_id", nullable = false)
    val createdByTripMemberId: Long,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
