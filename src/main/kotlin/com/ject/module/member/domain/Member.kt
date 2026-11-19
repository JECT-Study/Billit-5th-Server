package com.ject.module.member.domain

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
@Table(name = "member")
class Member(
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "social_provider", nullable = false)
    val socialProvider: AuthProvider,
    @field:Column(name = "social_provider_user_id", nullable = true)
    val socialProviderUserId: String?,
    @field:Column(name = "nickname", nullable = true)
    val nickname: String?,
    @field:Column(name = "name", nullable = true)
    val name: String?,
    @field:Column(name = "age", nullable = true)
    val age: Int?,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "gender", nullable = true)
    val gender: Gender?,
    @field:Column(name = "is_treasurer", nullable = true)
    val isTreasurer: Boolean?,
    @field:Column(name = "marketing_push_agreed_at", nullable = true)
    val marketingPushAgreedAt: Instant?,
    @field:Column(name = "marketing_night_push_agreed_at", nullable = true)
    val marketingNightPushAgreedAt: Instant?,
    @field:Column(name = "ocr_agreed_at", nullable = true)
    val ocrAgreedAt: Instant?,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "status", nullable = false)
    val status: MemberStatus,
    @field:Column(name = "withdrawn_at", nullable = true)
    val withdrawnAt: Instant?,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
