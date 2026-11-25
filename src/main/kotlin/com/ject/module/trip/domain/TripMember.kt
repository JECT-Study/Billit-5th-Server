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
    tripNickname: String?,
    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "role", nullable = false)
    val role: TripMemberRole,
    /** 초대 코드로 참여한 시각입니다. 여행 생성자와 비로그인 참여자처럼 초대 코드로 참여하지 않았으면 `null`입니다. */
    @field:Column(name = "joined_at", nullable = true)
    val joinedAt: Instant?,
    status: TripMemberStatus,
    leftAt: Instant?,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set

    /** 여행방 닉네임입니다. 로그인 참여자와 비로그인 참여자 모두 저장하며 같은 여행 안에서 겹칠 수 없습니다. */
    @field:Column(name = "trip_nickname", nullable = true)
    var tripNickname: String? = tripNickname
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @field:Column(name = "status", nullable = false)
    var status: TripMemberStatus = status
        protected set

    /** 여행을 나간 시각입니다. 나가지 않았으면 `null`이며, 나갔는지는 [status]로 판단합니다. */
    @field:Column(name = "left_at", nullable = true)
    var leftAt: Instant? = leftAt
        protected set

    /** 여행을 나갑니다. 지출과 정산에서 계속 참조하도록 행은 지우지 않습니다. */
    fun leave(leftAt: Instant) {
        this.status = TripMemberStatus.LEFT
        this.leftAt = leftAt
    }

    /** 나간 여행에 다시 참여합니다. 나가기 전의 참여 정보와 여행방 닉네임을 그대로 씁니다. */
    fun rejoin() {
        this.status = TripMemberStatus.MEMBER
        this.leftAt = null
    }

    /** 여행방 닉네임을 바꿉니다. 같은 여행의 다른 참여자와 겹치는지는 저장할 때 유니크 제약으로 확인합니다. */
    fun changeTripNickname(tripNickname: String) {
        this.tripNickname = tripNickname
    }
}
