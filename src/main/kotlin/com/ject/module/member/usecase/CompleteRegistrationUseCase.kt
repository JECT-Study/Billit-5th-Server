package com.ject.module.member.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.auth.domain.AuthTokenResult
import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.module.member.domain.CompleteRegistrationCommand
import com.ject.module.member.domain.ConsentChannel
import com.ject.module.member.domain.ConsentSource
import com.ject.module.member.domain.MemberConsent
import com.ject.module.member.domain.MemberErrorCode
import com.ject.module.member.domain.MemberPainPoint
import com.ject.module.member.domain.MemberRegistrationRepository
import com.ject.module.member.domain.MemberRepository
import com.ject.module.member.domain.MemberStatus
import com.ject.module.member.domain.PainPoint
import com.ject.module.member.domain.Policy
import com.ject.module.member.domain.PolicyType
import com.ject.module.member.domain.RegistrationCompletionResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * 닉네임과 약관 동의, 설문 응답을 저장하고 가입을 완료한 뒤 회원용 토큰을 발급합니다.
 *
 * 회원 상태 변경, 동의 이력, 설문 응답과 세션 저장을 한 트랜잭션에서 커밋합니다. 실패하면 모두 롤백하고 회원은 가입 중으로 남습니다.
 */
@Service
class CompleteRegistrationUseCase(
    private val memberRepository: MemberRepository,
    private val memberRegistrationRepository: MemberRegistrationRepository,
    private val authTokenCreator: AuthTokenCreator,
    private val clock: Clock,
) {
    /**
     * 1. 이미 가입을 마친 회원이면 응답을 받지 못한 재시도로 보고 요청 내용을 다시 저장하지 않고 새 세션의 토큰을 발급합니다. 가입 전용 토큰의 유효 시간 안에서만 가능합니다.
     * 2. 동의한 약관이 현재 버전인지와 필수 약관에 모두 동의했는지 확인합니다.
     * 3. 가입 중 회원만 가입 완료로 바꿉니다. 같은 회원의 요청이 동시에 오면 한 건만 저장하고 나머지는 1번처럼 토큰만 발급합니다.
     * 4. 마케팅 수신 동의는 일반 푸시 채널의 동의로 저장합니다. 야간 푸시 동의는 가입 때 받지 않습니다.
     */
    @Transactional
    fun complete(
        memberId: Long,
        command: CompleteRegistrationCommand,
    ): AuthTokenResult {
        if (currentStatus(memberId) == MemberStatus.ACTIVE) {
            return authTokenCreator.createMemberTokens(memberId)
        }
        validateSurvey(command)
        val now = clock.instant()
        val policies = memberRegistrationRepository.findCurrentSignupPolicies(now)
        validateAgreements(policies, command.agreements)

        val agreedPolicyIds =
            command.agreements
                .filter { it.agreed }
                .map { it.policyId }
                .toSet()
        val isMarketingAgreed = policies.any { it.type == PolicyType.MARKETING && it.id in agreedPolicyIds }
        val completion =
            memberRepository.completeRegistrationIfPending(
                memberId = memberId,
                nickname = command.nickname,
                gender = command.gender,
                isTreasurer = command.isTreasurer,
                marketingPushAgreedAt = if (isMarketingAgreed) now else null,
            )
        when (completion) {
            RegistrationCompletionResult.COMPLETED -> {
                Unit
            }

            RegistrationCompletionResult.NICKNAME_ALREADY_USED -> {
                throw BusinessException(MemberErrorCode.NICKNAME_ALREADY_USED)
            }

            RegistrationCompletionResult.NOT_PENDING -> {
                if (currentStatus(memberId) != MemberStatus.ACTIVE) {
                    throw BusinessException(CommonErrorCode.UNAUTHORIZED)
                }
                return authTokenCreator.createMemberTokens(memberId)
            }
        }

        val policyTypes = policies.associate { checkNotNull(it.id) to it.type }
        memberRegistrationRepository.saveConsents(
            command.agreements.map {
                MemberConsent(
                    memberId = memberId,
                    policyId = it.policyId,
                    channel = if (policyTypes.getValue(it.policyId) == PolicyType.MARKETING) ConsentChannel.PUSH else null,
                    agreed = it.agreed,
                    source = ConsentSource.SIGNUP,
                    platform = command.platform,
                    appVersion = null,
                )
            },
        )
        memberRegistrationRepository.savePainPoints(
            command.painPoints.map {
                MemberPainPoint(
                    memberId = memberId,
                    painPoint = it,
                    otherReasonText = if (it == PainPoint.ETC) command.otherPainPointText else null,
                )
            },
        )
        return authTokenCreator.createMemberTokens(memberId)
    }

    /** 회원이 없거나 탈퇴했으면 가입 전용 토큰으로 가입할 수 없으므로 401입니다. */
    private fun currentStatus(memberId: Long): MemberStatus {
        val status = memberRepository.findRegistrationMember(memberId)?.status
        if (status == null || status == MemberStatus.WITHDRAWN) {
            throw BusinessException(CommonErrorCode.UNAUTHORIZED)
        }
        return status
    }

    private fun validateSurvey(command: CompleteRegistrationCommand) {
        val isValid =
            command.painPoints.size <= MAX_PAIN_POINTS &&
                command.painPoints.toSet().size == command.painPoints.size &&
                (command.otherPainPointText == null || PainPoint.ETC in command.painPoints)
        if (!isValid) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }
    }

    /** 같은 약관을 두 번 보내거나 현재 버전이 아닌 약관을 보내면 400, 필수 약관의 동의가 없거나 동의하지 않았으면 필수 약관 누락입니다. */
    private fun validateAgreements(
        policies: List<Policy>,
        agreements: List<CompleteRegistrationCommand.PolicyAgreement>,
    ) {
        val policyIds = policies.map { checkNotNull(it.id) }.toSet()
        val submittedPolicyIds = agreements.map { it.policyId }
        if (submittedPolicyIds.toSet().size != submittedPolicyIds.size || !policyIds.containsAll(submittedPolicyIds)) {
            throw BusinessException(CommonErrorCode.INVALID_REQUEST)
        }

        val agreedPolicyIds = agreements.filter { it.agreed }.map { it.policyId }.toSet()
        if (policies.any { it.isRequired && it.id !in agreedPolicyIds }) {
            throw BusinessException(MemberErrorCode.REQUIRED_AGREEMENT_MISSING)
        }
    }

    companion object {
        private const val MAX_PAIN_POINTS = 2
    }
}
