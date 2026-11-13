package com.ject.module.auth.usecase

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
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
 * 제공자 JWKS 조회가 DB 트랜잭션을 붙잡지 않도록 ID 토큰을 먼저 검증합니다. 이후 쓰기는 회원 생성이나 세션 생성 중 한 건이므로 각 저장이 자체 트랜잭션으로 커밋되고,
 * 이 UseCase는 여러 쓰기를 하나로 묶는 트랜잭션을 열지 않습니다.
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
        val providerUserId = socialIdTokenPort.verify(provider, idToken)

        val member = memberRepository.findBySocialAccount(provider, providerUserId) ?: createPendingMember(provider, providerUserId)

        return when (member.status) {
            MemberStatus.ACTIVE -> SocialLoginResult.Authenticated(authTokenCreator.createMemberTokens(member.memberId))

            MemberStatus.PENDING -> SocialLoginResult.RegistrationRequired(authTokenCreator.createRegistrationToken(member.memberId))

            // 탈퇴하면 외부 사용자 ID를 지우므로 여기 오는 회원은 탈퇴 처리가 끝나지 않은 경우입니다. 재가입 정책이 정해지기 전까지 로그인을 거절합니다.
            MemberStatus.WITHDRAWN -> throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED)
        }
    }

    /** 같은 소셜 계정의 최초 로그인이 동시에 들어오면 유니크 제약으로 한 건만 저장되고, 나머지는 저장된 회원을 다시 조회합니다. */
    private fun createPendingMember(
        provider: AuthProvider,
        providerUserId: String,
    ): SocialMemberResult {
        val memberId = memberRepository.createPendingMember(provider, providerUserId)
        if (memberId != null) {
            return SocialMemberResult(memberId = memberId, status = MemberStatus.PENDING)
        }
        return checkNotNull(memberRepository.findBySocialAccount(provider, providerUserId)) {
            "소셜 계정 중복으로 저장하지 못했지만 회원을 찾지 못했습니다."
        }
    }
}
