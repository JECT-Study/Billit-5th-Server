package com.ject.module.exchangerate.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 결제일([rateDate])마다 한 행으로 저장한 기준 환율입니다. 저장한 뒤에는 갱신하거나 삭제하지 않습니다.
 *
 * 행은 [ReferenceExchangeRateRepository.saveIfAbsent]의 `INSERT ... ON CONFLICT DO NOTHING`으로만 저장하며, JPA로 저장하지 않습니다.
 */
@Entity
@Table(name = "reference_exchange_rate")
class ReferenceExchangeRate(
    @field:Column(name = "provider", nullable = false)
    val provider: String,
    @field:JdbcTypeCode(SqlTypes.CHAR)
    @field:Column(name = "currency", nullable = false)
    val currency: String,
    @field:Column(name = "rate_date", nullable = false)
    val rateDate: LocalDate,
    @field:Column(name = "base_date", nullable = false)
    val baseDate: LocalDate,
    @field:Column(name = "rate", nullable = false)
    val rate: BigDecimal,
    @field:Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
