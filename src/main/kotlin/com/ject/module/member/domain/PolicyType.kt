package com.ject.module.member.domain

enum class PolicyType {
    TERMS,
    PRIVACY,
    MARKETING,
    OCR,
    ;

    companion object {
        /** 가입할 때 동의받는 약관 종류입니다. OCR 동의는 가입 때 받지 않습니다. */
        val SIGNUP_TYPES: Set<PolicyType> = setOf(TERMS, PRIVACY, MARKETING)
    }
}
