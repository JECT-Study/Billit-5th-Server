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
    name: String,
    startDate: LocalDate,
    endDate: LocalDate,
    countryCode: TripCountryCode,
    customCountryName: String?,
    baseCurrency: TripCurrency,
    memberCapacity: Int,
    status: TripStatus,
    confirmedAt: Instant?,
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

    @field:Column(name = "name", nullable = false)
    var name: String = name
        protected set

    @field:Column(name = "start_date", nullable = false)
    var startDate: LocalDate = startDate
        protected set

    @field:Column(name = "end_date", nullable = false)
    var endDate: LocalDate = endDate
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "country_code", nullable = false)
    var countryCode: TripCountryCode = countryCode
        protected set

    /** 기타 국가로 직접 입력한 국가명입니다. [countryCode]가 [TripCountryCode.ETC]가 아니면 `null`입니다. */
    @field:Column(name = "custom_country_name", nullable = true)
    var customCountryName: String? = customCountryName
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.CHAR)
    @field:Column(name = "base_currency", nullable = false)
    var baseCurrency: TripCurrency = baseCurrency
        protected set

    /** 생성자를 포함한 총인원입니다. */
    @field:Column(name = "member_capacity", nullable = false)
    var memberCapacity: Int = memberCapacity
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "status", nullable = false)
    var status: TripStatus = status
        protected set

    /** 여행을 확정한 시각입니다. 확정 전이면 `null`이며, 확정 여부는 [status]로 판단합니다. */
    @field:Column(name = "confirmed_at", nullable = true)
    var confirmedAt: Instant? = confirmedAt
        protected set

    @field:Column(name = "deleted_at")
    var deletedAt: Instant? = null
        protected set

    fun isDeleted(): Boolean = deletedAt != null

    /** 기본 정보와 총인원을 바꿉니다. 값의 조합과 총인원을 바꿀 수 있는지는 호출하는 쪽이 확인합니다. */
    fun update(
        name: String,
        countryCode: TripCountryCode,
        customCountryName: String?,
        baseCurrency: TripCurrency,
        startDate: LocalDate,
        endDate: LocalDate,
        memberCapacity: Int,
    ) {
        this.name = name
        this.countryCode = countryCode
        this.customCountryName = customCountryName
        this.baseCurrency = baseCurrency
        this.startDate = startDate
        this.endDate = endDate
        this.memberCapacity = memberCapacity
    }

    /** 참여자 구성을 확정합니다. 확정 전으로 되돌리지 않습니다. */
    fun confirm(confirmedAt: Instant) {
        this.status = TripStatus.CONFIRMED
        this.confirmedAt = confirmedAt
    }
}
