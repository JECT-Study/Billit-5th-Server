package com.ject.module.trip.domain

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
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "trip")
class Trip(
    @field:Column(name = "name", nullable = false)
    val name: String,
    @field:Column(name = "start_date", nullable = false)
    val startDate: LocalDate,
    @field:Column(name = "end_date", nullable = false)
    val endDate: LocalDate,
    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "country_code", nullable = false)
    val countryCode: TripCountryCode,
    /** 기타 국가로 직접 입력한 국가명입니다. [countryCode]가 [TripCountryCode.ETC]가 아니면 `null`입니다. */
    @field:Column(name = "custom_country_name", nullable = true)
    val customCountryName: String?,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.CHAR)
    @field:Column(name = "base_currency", nullable = false)
    val baseCurrency: TripCurrency,
    /** 생성자를 포함한 총인원입니다. */
    @field:Column(name = "member_capacity", nullable = false)
    val memberCapacity: Int,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "status", nullable = false)
    val status: TripStatus,
    /** 여행을 확정한 시각입니다. 확정 전이면 `null`이며, 확정 여부는 [status]로 판단합니다. */
    @field:Column(name = "confirmed_at", nullable = true)
    val confirmedAt: Instant?,
    @field:Column(name = "invite_code", nullable = false)
    val inviteCode: String,
    /** 앱이 여행 생성 요청마다 만든 고유키입니다. 같은 키로는 여행을 한 번만 만듭니다. */
    @field:Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: UUID,
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
