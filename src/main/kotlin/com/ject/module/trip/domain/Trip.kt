package com.ject.module.trip.domain

import com.ject.global.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "trip")
class Trip(
    @field:Column(name = "name", nullable = false)
    val name: String,
    @field:Column(name = "start_date", nullable = false)
    val startDate: LocalDate,
    @field:Column(name = "end_date", nullable = false)
    val endDate: LocalDate,
    @field:JdbcTypeCode(SqlTypes.CHAR)
    @field:Column(name = "country_code", nullable = false)
    val countryCode: String,
    @field:JdbcTypeCode(SqlTypes.CHAR)
    @field:Column(name = "base_currency", nullable = false)
    val baseCurrency: String,
    @field:Column(name = "member_capacity", nullable = false)
    val memberCapacity: Int,
    @field:Column(name = "members_locked_at", nullable = true)
    val membersLockedAt: Instant?,
    @field:Column(name = "invite_code", nullable = false)
    val inviteCode: String,
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
