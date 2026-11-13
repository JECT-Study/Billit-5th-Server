package com.ject.module.auth.domain

/** 회원당 하나의 애플 Refresh Token을 암호화해 저장합니다. 원문은 이 경계 밖으로 저장 형식을 드러내지 않습니다. */
interface AppleRefreshTokenRepository {
    /**
     * [memberId] 회원의 애플 Refresh Token을 암호화해 저장합니다. 이미 있으면 새 토큰으로 교체합니다.
     *
     * 동시에 저장해도 회원당 한 행만 남도록 한 번의 UPSERT를 자체 트랜잭션에서 커밋합니다.
     */
    fun save(
        memberId: Long,
        refreshToken: String,
    )

    /** [memberId] 회원의 애플 Refresh Token 원문을 반환합니다. 저장된 토큰이 없으면 `null`입니다. */
    fun findRefreshToken(memberId: Long): String?

    /** [memberId] 회원의 애플 Refresh Token을 삭제합니다. 삭제했으면 `true`입니다. 자체 트랜잭션에서 커밋합니다. */
    fun deleteByMemberId(memberId: Long): Boolean
}
