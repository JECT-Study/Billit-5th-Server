package com.ject.module.member.domain

interface MemberRepository {
    /** 제공자와 외부 사용자 ID(`sub`)로 회원을 찾습니다. 탈퇴로 외부 사용자 ID를 지운 회원은 찾지 않습니다. */
    fun findBySocialAccount(
        socialProvider: AuthProvider,
        socialProviderUserId: String,
    ): SocialMemberResult?

    /**
     * 처음 소셜 로그인한 사용자를 가입 중(`PENDING`) 회원으로 저장하고 회원 ID를 반환합니다.
     *
     * - 호출 전 검증: 호출하는 쪽이 ID 토큰을 검증하고 같은 소셜 계정의 회원이 없는지 조회합니다.
     * - 같은 소셜 계정의 회원이 그 사이 저장됐으면 `uq_member_social_provider_social_provider_user_id` 위반으로 판단해 `null`을 반환합니다. 다른 DB 오류는 그대로 던집니다.
     * - 트랜잭션: 자체 트랜잭션에서 저장하고 커밋합니다. 중복 위반 뒤 다시 조회할 수 있도록 다른 트랜잭션 안에서 호출하지 않습니다.
     */
    fun createPendingMember(
        socialProvider: AuthProvider,
        socialProviderUserId: String,
    ): Long?
}
