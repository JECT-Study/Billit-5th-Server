package com.ject.module.member.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.member.domain.MemberProfileResult
import com.ject.module.member.domain.MemberRepository
import org.springframework.stereotype.Service

/** 설정과 프로필 화면에 표시할 본인 계정 정보를 조회합니다. 가입을 마친 회원만 조회할 수 있고, 그 밖이면 401입니다. */
@Service
class GetMyProfileUseCase(
    private val memberRepository: MemberRepository,
) {
    fun get(memberId: Long): MemberProfileResult =
        memberRepository.findActiveProfile(memberId) ?: throw BusinessException(CommonErrorCode.UNAUTHORIZED)
}
