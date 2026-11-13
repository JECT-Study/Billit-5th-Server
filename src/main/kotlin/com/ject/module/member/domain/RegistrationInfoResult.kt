package com.ject.module.member.domain

/** 가입 화면에 표시할 본인 정보와 동의받을 약관입니다. */
data class RegistrationInfoResult(
    val socialDisplayName: String?,
    val name: String?,
    val age: Int?,
    val terms: List<Terms>,
) {
    data class Terms(
        val policyId: Long,
        val title: String,
        val isRequired: Boolean,
        val contentUrl: String,
    )
}
