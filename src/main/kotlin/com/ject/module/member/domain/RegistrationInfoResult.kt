package com.ject.module.member.domain

/** 가입 화면에서 동의받을 약관입니다. */
data class RegistrationInfoResult(
    val terms: List<Terms>,
) {
    data class Terms(
        val policyId: Long,
        val title: String,
        val isRequired: Boolean,
        val contentUrl: String,
    )
}
