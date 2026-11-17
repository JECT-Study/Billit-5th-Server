package com.ject.module.member.domain

import com.ject.global.error.ErrorCode

/** 회원 API의 오류 코드입니다. API 명세의 회원 코드 접두어 `USER_`를 따릅니다. */
enum class MemberErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    REQUIRED_AGREEMENT_MISSING(400, "필수 약관에 동의해주세요."),
    ;

    override val code: String = "USER_$name"
}
