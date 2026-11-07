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
@Table(name = "member_consent")
@EntityListeners(AuditingEntityListener::class)
class MemberConsent(
    @field:Column(name = "member_id", nullable = false)
    val memberId: Long,
    @field:Column(name = "policy_id", nullable = false)
    val policyId: Long,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "channel", nullable = true)
    val channel: ConsentChannel?,
    @field:Column(name = "agreed", nullable = false)
    val agreed: Boolean,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "source", nullable = false)
    val source: ConsentSource,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "platform", nullable = false)
    val platform: PlatformType,
    @field:Column(name = "app_version", nullable = true)
    val appVersion: String?,
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
