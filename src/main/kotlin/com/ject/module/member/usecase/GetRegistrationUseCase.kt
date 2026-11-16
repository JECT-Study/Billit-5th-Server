package com.ject.module.member.usecase

import com.ject.module.member.domain.MemberRegistrationRepository
import com.ject.module.member.domain.RegistrationInfoResult
import com.ject.module.member.implement.PolicyContentUrlCreator
import com.ject.module.member.implement.RegistrationMemberReader
import org.springframework.stereotype.Service
import java.time.Clock

/** 가입 화면에 표시할 본인 정보와 동의받을 약관의 현재 버전을 조회합니다. 가입 중 회원만 조회할 수 있습니다. */
@Service
class GetRegistrationUseCase(
    private val registrationMemberReader: RegistrationMemberReader,
    private val memberRegistrationRepository: MemberRegistrationRepository,
    private val policyContentUrlCreator: PolicyContentUrlCreator,
    private val clock: Clock,
) {
    fun get(memberId: Long): RegistrationInfoResult {
        val member = registrationMemberReader.readPending(memberId)

        val terms =
            memberRegistrationRepository.findCurrentSignupPolicies(clock.instant()).map {
                RegistrationInfoResult.Terms(
                    policyId = checkNotNull(it.id),
                    title = it.title,
                    isRequired = it.isRequired,
                    contentUrl = policyContentUrlCreator.create(it),
                )
            }
        return RegistrationInfoResult(
            socialDisplayName = member.socialDisplayName,
            terms = terms,
        )
    }
}
