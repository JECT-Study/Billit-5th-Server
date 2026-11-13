package com.ject.module.auth.usecase

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.domain.SocialIdentity
import com.ject.module.auth.domain.SocialLoginResult
import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.module.auth.usecase.port.SocialIdTokenPort
import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.MemberRepository
import com.ject.module.member.domain.MemberStatus
import com.ject.module.member.domain.SocialMemberResult
import org.springframework.stereotype.Service

/**
 * 구글과 카카오 ID 토큰으로 로그인하거나 가입 절차를 시작합니다.
 *
 * 제공자 JWKS 조회가 DB 트랜잭션을 붙잡지 않도록 ID 토큰을 먼저 검증합니다. 이후 쓰기는 회원 생성, 소셜 표시 이름 저장, 세션 생성이며 각 저장이 자체 트랜잭션으로 커밋됩니다.
 * 이 UseCase는 여러 쓰기를 하나로 묶는 트랜잭션을 열지 않습니다. 표시 이름 저장 뒤 세션 생성이 실패해도 표시 이름은 남으며, 가입 화면의 초기값이라 다음 로그인에 영향을 주지 않습니다.
 */
@Service
class SocialLoginUseCase(
    private val socialIdTokenPort: SocialIdTokenPort,
    private val memberRepository: MemberRepository,
    private val authTokenCreator: AuthTokenCreator,
) {
    fun login(
        provider: AuthProvider,
        idToken: String,
    ): SocialLoginResult {
        val identity = socialIdTokenPort.verify(provider, idToken)

        val member = findOrCreateMember(provider, identity)

        return when (member.status) {
            MemberStatus.ACTIVE -> SocialLoginResult.Authenticated(authTokenCreator.createMemberTokens(member.memberId))

            MemberStatus.PENDING -> SocialLoginResult.RegistrationRequired(authTokenCreator.createRegistrationToken(member.memberId))

            // 탈퇴하면 외부 사용자 ID를 지우므로 여기 오는 회원은 탈퇴 처리가 끝나지 않은 경우입니다. 재가입 정책이 정해지기 전까지 로그인을 거절합니다.
            MemberStatus.WITHDRAWN -> throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED)
        }
    }

    /**
     * 소셜 계정의 회원을 찾고, 없으면 가입 중 회원으로 만듭니다.
     *
     * 가입 중 회원에게 저장된 표시 이름이 없고 이번에 받았으면 채웁니다. 이미 있으면 덮어쓰지 않습니다.
     */
    private fun findOrCreateMember(
        provider: AuthProvider,
        identity: SocialIdentity,
    ): SocialMemberResult {
        val member = memberRepository.findBySocialAccount(provider, identity.subject) ?: return createPendingMember(provider, identity)
        if (member.status == MemberStatus.PENDING && member.socialDisplayName == null && identity.displayName != null) {
            memberRepository.fillSocialDisplayNameIfAbsent(member.memberId, identity.displayName)
        }
        return member
    }

    /** 같은 소셜 계정의 최초 로그인이 동시에 들어오면 유니크 제약으로 한 건만 저장되고, 나머지는 저장된 회원을 다시 조회합니다. */
    private fun createPendingMember(
        provider: AuthProvider,
        identity: SocialIdentity,
    ): SocialMemberResult {
        val memberId = memberRepository.createPendingMember(provider, identity.subject, identity.displayName)
        if (memberId != null) {
            return SocialMemberResult(memberId = memberId, status = MemberStatus.PENDING, socialDisplayName = identity.displayName)
        }
        return checkNotNull(memberRepository.findBySocialAccount(provider, identity.subject)) {
            "소셜 계정 중복으로 저장하지 못했지만 회원을 찾지 못했습니다."
        }
    }
}
