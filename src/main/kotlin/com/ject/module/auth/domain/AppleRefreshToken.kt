package com.ject.module.auth.domain

import com.ject.global.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 애플 연결 해제에 쓸 회원의 애플 Refresh Token 암호문입니다. 저장과 교체는 [AppleRefreshTokenRepository]가 암호화해 처리합니다. */
@Entity
@Table(name = "apple_refresh_token")
class AppleRefreshToken(
    @field:Column(name = "member_id", nullable = false)
    val memberId: Long,
    @field:Column(name = "encrypted_refresh_token", nullable = false)
    val encryptedRefreshToken: String,
) : BaseEntity() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id")
    var id: Long? = null
        protected set
}
