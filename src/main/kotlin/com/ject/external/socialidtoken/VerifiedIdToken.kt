package com.ject.external.socialidtoken

/** 검증을 마친 ID 토큰의 `sub`와 문자열 클레임 조회입니다. */
class VerifiedIdToken(
    val subject: String,
    private val claims: Map<String, Any>,
) {
    /** 클레임이 없거나 문자열이 아니면 `null`입니다. */
    fun stringClaim(name: String): String? = claims[name] as? String
}
