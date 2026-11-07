package com.ject.module.member.domain

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
@Table(name = "policy")
@EntityListeners(AuditingEntityListener::class)
class Policy(
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "type", nullable = false)
    val type: PolicyType,
    @field:Column(name = "version", nullable = false)
    val version: String,
    @field:Column(name = "title", nullable = false)
    val title: String,
    @field:JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @field:Column(name = "content", nullable = false)
    val content: String,
    @field:Column(name = "is_required", nullable = false)
    val isRequired: Boolean,
    @field:Column(name = "effective_at", nullable = false)
    val effectiveAt: Instant,
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
