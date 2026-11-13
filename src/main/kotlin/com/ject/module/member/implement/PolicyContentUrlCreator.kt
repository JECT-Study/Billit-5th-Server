package com.ject.module.member.implement

import com.ject.module.member.domain.Policy

/**
 * 약관 전문을 볼 수 있는 주소를 만듭니다.
 *
 * 주소는 `{기본 주소}/{약관 종류 소문자}/{버전}`입니다. 예: `https://example.com/terms/privacy/v1`
 */
class PolicyContentUrlCreator(
    baseUrl: String,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    fun create(policy: Policy): String = "$baseUrl/${policy.type.name.lowercase()}/${policy.version}"
}
