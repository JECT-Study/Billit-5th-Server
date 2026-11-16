package com.ject.module.member.implement

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.member.domain.MemberRepository
import com.ject.module.member.domain.MemberStatus
import com.ject.module.member.domain.RegistrationMemberResult
import org.springframework.stereotype.Component

/** 가입 전용 토큰의 회원이 가입 중인지 확인하고 가입 화면에 표시할 값을 읽습니다. */
@Component
class RegistrationMemberReader(
    private val memberRepository: MemberRepository,
) {
    /** 가입 중 회원만 반환합니다. 가입을 마친 회원은 403, 회원이 없거나 탈퇴했으면 401입니다. */
    fun readPending(memberId: Long): RegistrationMemberResult {
        val member = memberRepository.findRegistrationMember(memberId) ?: throw BusinessException(CommonErrorCode.UNAUTHORIZED)
        return when (member.status) {
            MemberStatus.PENDING -> member
            MemberStatus.ACTIVE -> throw BusinessException(CommonErrorCode.FORBIDDEN)
            MemberStatus.WITHDRAWN -> throw BusinessException(CommonErrorCode.UNAUTHORIZED)
        }
    }
}
