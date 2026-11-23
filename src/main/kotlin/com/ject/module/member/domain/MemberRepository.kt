package com.ject.module.member.domain

import java.time.Instant

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
     * - [socialDisplayName]은 제공자가 준 표시 이름이며 없으면 `null`입니다. [SOCIAL_DISPLAY_NAME_MAX_LENGTH]자를 넘으면 앞부분만 저장합니다.
     * - 같은 소셜 계정의 회원이 그 사이 저장됐으면 `uq_member_social_provider_social_provider_user_id` 위반으로 판단해 `null`을 반환합니다. 다른 DB 오류는 그대로 던집니다.
     * - 트랜잭션: 자체 트랜잭션에서 저장하고 커밋합니다. 중복 위반 뒤 다시 조회할 수 있도록 다른 트랜잭션 안에서 호출하지 않습니다.
     */
    fun createPendingMember(
        socialProvider: AuthProvider,
        socialProviderUserId: String,
        socialDisplayName: String?,
    ): Long?

    /**
     * 가입 중(`PENDING`) 회원에게 저장된 소셜 표시 이름이 없을 때만 [socialDisplayName]을 저장합니다.
     *
     * - 이미 값이 있으면 덮어쓰지 않습니다. 애플은 표시 이름을 최초 권한 부여 때 한 번만 주므로 먼저 저장된 값을 유지합니다.
     * - [SOCIAL_DISPLAY_NAME_MAX_LENGTH]자를 넘으면 앞부분만 저장합니다.
     * - 저장했으면 `true`, 회원이 없거나 가입 중이 아니거나 이미 값이 있으면 `false`를 반환합니다.
     * - 트랜잭션: 한 번의 조건부 UPDATE를 자체 트랜잭션에서 커밋합니다.
     */
    fun fillSocialDisplayNameIfAbsent(
        memberId: Long,
        socialDisplayName: String,
    ): Boolean

    /** 가입 절차에서 확인할 회원의 현재 상태와 표시 값을 조회합니다. 영속성 컨텍스트에 남은 값이 아니라 조회 시점의 DB 값을 반환합니다. */
    fun findRegistrationMember(memberId: Long): RegistrationMemberResult?

    /**
     * 가입 중(`PENDING`) 회원에게 닉네임과 설문 응답, 마케팅 푸시 동의 시각을 저장하고 가입 완료(`ACTIVE`)로 바꿉니다.
     *
     * - 호출 전 검증: 호출하는 쪽이 닉네임 형식과 필수 약관 동의를 확인합니다.
     * - 변경 조건: 회원이 가입 중일 때만 바꿉니다. 같은 회원의 요청이 동시에 오면 한 건만 [RegistrationCompletionResult.COMPLETED]이고 나머지는 [RegistrationCompletionResult.NOT_PENDING]입니다.
     * - 닉네임은 다른 회원과 겹쳐도 됩니다. DB 오류는 그대로 던집니다.
     * - 트랜잭션: 호출하는 UseCase의 트랜잭션에 참여합니다.
     */
    fun completeRegistrationIfPending(
        memberId: Long,
        nickname: String,
        gender: Gender?,
        isTreasurer: Boolean?,
        marketingPushAgreedAt: Instant?,
    ): RegistrationCompletionResult

    /** [memberIds] 중 탈퇴 시각이 기록된 회원과 그 탈퇴 시각을 조회합니다. 탈퇴 시각이 없거나 없는 회원은 결과에서 빠지며 순서는 보장하지 않습니다. */
    fun findWithdrawnMembers(memberIds: Collection<Long>): List<WithdrawnMemberResult>

    companion object {
        /** `member.social_display_name`의 최대 길이(문자 수)입니다. */
        const val SOCIAL_DISPLAY_NAME_MAX_LENGTH = 100
    }
}
