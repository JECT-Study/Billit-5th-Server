package com.ject.module.auth.domain

/**
 * 저장된 애플 Refresh Token을 읽은 결과입니다.
 *
 * [encryptedRefreshToken]은 읽은 행의 저장값이며, 지울 때 그사이 새 토큰으로 교체되지 않았는지 비교하는 데만 씁니다.
 */
sealed interface StoredAppleRefreshToken {
    val encryptedRefreshToken: String

    /** 복호화한 애플 Refresh Token 원문입니다. */
    class Decrypted(
        val refreshToken: String,
        override val encryptedRefreshToken: String,
    ) : StoredAppleRefreshToken

    /** 토큰 암호화 키가 바뀌었거나 저장값이 손상돼 복호화하지 못했습니다. */
    class Undecryptable(
        override val encryptedRefreshToken: String,
    ) : StoredAppleRefreshToken
}
