package com.ject.module.auth.usecase

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AppleRefreshTokenRepository
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.domain.SocialIdentity
import com.ject.module.auth.domain.SocialLoginResult
import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.module.auth.usecase.port.AppleAuthorizationPort
import com.ject.module.auth.usecase.port.SocialIdTokenPort
import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.MemberRepository
import com.ject.module.member.domain.MemberStatus
import com.ject.module.member.domain.SocialMemberResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

/**
 * 소셜 계정으로 로그인하거나 가입 절차를 시작합니다. 구글과 카카오는 ID 토큰, 애플은 ID 토큰과 인가 코드로 인증합니다.
 *
 * 제공자 호출(JWKS 조회, 애플 인가 코드 교환)이 DB 트랜잭션을 붙잡지 않도록 외부 호출을 모두 마친 뒤 DB에 씁니다.
 * 이후 쓰기는 회원 생성, 소셜 표시 이름 저장, 애플 Refresh Token 저장, 세션 생성이며 각 저장이 자체 트랜잭션으로 커밋됩니다.
 * 이 UseCase는 여러 쓰기를 하나로 묶는 트랜잭션을 열지 않습니다. 중간에 실패해도 먼저 저장한 값은 다음 로그인에서 재사용하거나 교체되므로 로그인 결과에 영향을 주지 않습니다.
 */
@Service
class SocialLoginUseCase(
    private val socialIdTokenPort: SocialIdTokenPort,
    private val appleAuthorizationPort: AppleAuthorizationPort,
    private val memberRepository: MemberRepository,
    private val appleRefreshTokenRepository: AppleRefreshTokenRepository,
    private val authTokenCreator: AuthTokenCreator,
) {
    /** 구글과 카카오 ID 토큰으로 로그인합니다. */
    fun login(
        provider: AuthProvider,
        idToken: String,
    ): SocialLoginResult {
        require(provider != AuthProvider.APPLE) { "애플 로그인은 인가 코드가 필요합니다." }
        val identity = socialIdTokenPort.verify(provider, idToken)

        val member = findOrCreateMember(provider, identity)

        rejectWithdrawnMember(member)
        return issueLoginResult(member)
    }

    /**
     * 애플 ID 토큰과 인가 코드로 로그인합니다.
     *
     * 1. ID 토큰을 먼저 검증해, 유효하지 않으면 인가 코드를 쓰지 않고 401로 실패합니다.
     * 2. 인가 코드를 교환합니다. 인가 코드는 한 번만 교환할 수 있어 응답을 받지 못해 같은 코드로 재시도하면 401이며, 앱은 소셜 인증을 다시 진행합니다.
     * 3. 교환 응답의 ID 토큰이 다른 사용자이면 401로 실패하고 회원을 만들지 않습니다.
     * 4. [fullName]은 애플이 최초 권한 부여 때만 주므로 소셜 표시 이름으로 저장하고 이후 덮어쓰지 않습니다.
     * 5. 교환으로 받은 애플 Refresh Token을 탈퇴 시 연결 해제용으로 암호화해 저장합니다. 이미 있으면 교체합니다.
     */
    fun loginWithApple(
        idToken: String,
        authorizationCode: String,
        fullName: String?,
    ): SocialLoginResult {
        val identity = socialIdTokenPort.verify(AuthProvider.APPLE, idToken)
        val authorization = appleAuthorizationPort.exchangeAuthorizationCode(authorizationCode)
        if (authorization.subject != identity.subject) {
            log.warn { "event=apple_authorization_subject_mismatch" }
            throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED)
        }

        val member = findOrCreateMember(AuthProvider.APPLE, identity.copy(displayName = fullName))

        rejectWithdrawnMember(member)
        if (authorization.refreshToken == null) {
            log.warn { "event=apple_refresh_token_missing memberId=${member.memberId}" }
        } else {
            appleRefreshTokenRepository.save(member.memberId, authorization.refreshToken)
        }
        return issueLoginResult(member)
    }

    /** 탈퇴하면 외부 사용자 ID를 지우므로 여기 오는 회원은 탈퇴 처리가 끝나지 않은 경우입니다. 재가입 정책이 정해지기 전까지 로그인을 거절합니다. */
    private fun rejectWithdrawnMember(member: SocialMemberResult) {
        if (member.status == MemberStatus.WITHDRAWN) {
            throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED)
        }
    }

    private fun issueLoginResult(member: SocialMemberResult): SocialLoginResult =
        when (member.status) {
            MemberStatus.ACTIVE -> SocialLoginResult.Authenticated(authTokenCreator.createMemberTokens(member.memberId))
            MemberStatus.PENDING -> SocialLoginResult.RegistrationRequired(authTokenCreator.createRegistrationToken(member.memberId))
            MemberStatus.WITHDRAWN -> throw BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED)
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

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
