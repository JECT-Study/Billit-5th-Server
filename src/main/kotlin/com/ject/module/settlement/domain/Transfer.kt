package com.ject.module.settlement.domain

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

@Entity
@Table(name = "transfer")
class Transfer(
    @field:Column(name = "settlement_id", nullable = false)
    val settlementId: Long,
    @field:Column(name = "trip_id", nullable = false)
    val tripId: Long,
    @field:Column(name = "sender_trip_member_id", nullable = false)
    val senderTripMemberId: Long,
    @field:Column(name = "receiver_trip_member_id", nullable = false)
    val receiverTripMemberId: Long,
    @field:JdbcTypeCode(SqlTypes.CHAR)
    @field:Column(name = "currency", nullable = false)
    val currency: String,
    @field:Column(name = "amount", nullable = false)
    val amount: Long,
    @field:Column(name = "received_at", nullable = true)
    val receivedAt: Instant?,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
