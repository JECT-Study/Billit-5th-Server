package com.ject.module.member.domain

/**
 * 가입 완료 입력입니다.
 *
 * [nickname]이 `null`이면 임의 닉네임을 부여합니다.
 * [painPoints]는 선택한 순서대로 최대 2개이며 중복이 없어야 합니다. [otherPainPointText]는 [painPoints]에 [PainPoint.ETC]가 있을 때만 보낼 수 있습니다.
 */
data class CompleteRegistrationCommand(
    val nickname: String?,
    val agreements: List<PolicyAgreement>,
    val platform: PlatformType,
    val appVersion: String,
    val gender: Gender?,
    val isTreasurer: Boolean?,
    val painPoints: List<PainPoint>,
    val otherPainPointText: String?,
) {
    /** 약관 버전 하나에 대한 동의 여부입니다. */
    data class PolicyAgreement(
        val policyId: Long,
        val agreed: Boolean,
    )
}
