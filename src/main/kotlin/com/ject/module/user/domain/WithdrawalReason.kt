package com.ject.module.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant

@Entity
@Table(name = "withdrawal_reason")
@EntityListeners(AuditingEntityListener::class)
class WithdrawalReason(
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "reason", nullable = false)
    val reason: WithdrawalReasonType,
    @field:Column(name = "other_reason_text", nullable = true)
    val otherReasonText: String?,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set

    @field:CreatedDate
    @field:Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null
        protected set
}
