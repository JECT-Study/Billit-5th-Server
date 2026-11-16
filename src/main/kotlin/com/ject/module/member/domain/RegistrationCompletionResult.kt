package com.ject.module.member.domain

/** 가입 중 회원을 가입 완료로 바꾼 결과입니다. */
enum class RegistrationCompletionResult {
    /** 가입 중 회원을 가입 완료로 바꿨습니다. */
    COMPLETED,

    /** 회원이 없거나 가입 중이 아니어서 바꾸지 않았습니다. */
    NOT_PENDING,

    /** 다른 회원이 같은 닉네임을 쓰고 있어 바꾸지 않았습니다. 현재 트랜잭션은 더 사용할 수 없습니다. */
    NICKNAME_ALREADY_USED,
}
