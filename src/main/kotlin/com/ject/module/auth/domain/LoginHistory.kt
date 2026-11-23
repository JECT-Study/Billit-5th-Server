package com.ject.module.auth.domain

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

/**
 * 회원의 로그인과 토큰 갱신 기록입니다. 통신비밀보호법 시행령 제41조의 로그기록자료와 접속지 추적자료(IP 주소)를 3개월 보관하려고 남깁니다.
 *
 * 저장한 뒤 바꾸지 않으므로 수정 시각을 관리하는 BaseEntity를 상속하지 않고, 발생 시각을 [occurredAt]에 직접 기록합니다.
 */
@Entity
@Table(name = "login_history")
class LoginHistory(
    @field:Column(name = "member_id", nullable = false)
    val memberId: Long,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "login_type", nullable = false)
    val loginType: LoginHistoryType,
    @field:Column(name = "ip_address", nullable = true)
    val ipAddress: String?,
    @field:Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
