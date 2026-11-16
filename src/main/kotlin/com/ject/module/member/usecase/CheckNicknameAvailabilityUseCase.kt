package com.ject.module.member.usecase

import com.ject.module.member.domain.MemberRepository
import com.ject.module.member.implement.RegistrationMemberReader
import org.springframework.stereotype.Service

/**
 * 닉네임의 현재 사용 가능 여부를 조회합니다. 가입 중 회원만 조회할 수 있습니다.
 *
 * 사용 가능해도 닉네임을 예약하지 않으며, 중복은 가입 완료 때 DB 제약으로 다시 확인합니다.
 */
@Service
class CheckNicknameAvailabilityUseCase(
    private val registrationMemberReader: RegistrationMemberReader,
    private val memberRepository: MemberRepository,
) {
    fun isAvailable(
        memberId: Long,
        nickname: String,
    ): Boolean {
        registrationMemberReader.readPending(memberId)

        return !memberRepository.existsByNickname(nickname)
    }
}
