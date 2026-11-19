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

@Entity
@Table(name = "trip_member")
class TripMember(
    @field:Column(name = "trip_id", nullable = false)
    val tripId: Long,
    /** 참여한 회원입니다. 비로그인 참여자이면 `null`입니다. */
    @field:Column(name = "member_id", nullable = true)
    val memberId: Long?,
    /** 여행방 닉네임입니다. 로그인 참여자와 비로그인 참여자 모두 저장하며 같은 여행 안에서 겹칠 수 없습니다. */
    @field:Column(name = "guest_name", nullable = true)
    val guestName: String?,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "role", nullable = false)
    val role: TripMemberRole,
    /** 초대 코드로 참여한 시각입니다. 여행 생성자와 비로그인 참여자처럼 초대 코드로 참여하지 않았으면 `null`입니다. */
    @field:Column(name = "joined_at", nullable = true)
    val joinedAt: Instant?,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
