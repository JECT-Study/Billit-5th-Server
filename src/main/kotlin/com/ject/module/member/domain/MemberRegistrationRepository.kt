package com.ject.module.member.domain

import java.time.Instant

/** 가입 절차의 약관 조회와 동의, 설문 저장을 담당합니다. 회원 행의 조회와 변경은 [MemberRepository]가 담당합니다. */
interface MemberRegistrationRepository {
    /**
     * 가입할 때 동의받는 약관([PolicyType.SIGNUP_TYPES])의 현재 버전을 약관 종류 선언 순서로 반환합니다.
     *
     * 현재 버전은 종류별로 시행 시각이 [now] 이전인 버전 중 시행 시각이 가장 늦은 것입니다. 시행 시각이 같으면 나중에 등록한 버전입니다. 시행 중인 버전이 없는 종류는 결과에 없습니다.
     */
    fun findCurrentSignupPolicies(now: Instant): List<Policy>

    /** 동의 이력을 저장합니다. 트랜잭션은 호출하는 UseCase가 엽니다. */
    fun saveConsents(consents: List<MemberConsent>)

    /** 설문에서 선택한 불편 사항을 저장합니다. 트랜잭션은 호출하는 UseCase가 엽니다. */
    fun savePainPoints(painPoints: List<MemberPainPoint>)
}
