package com.ject.module.auth.infra.persistence

import com.ject.module.auth.domain.AppleRefreshTokenRepository
import com.ject.module.auth.infra.TokenEncryptor
import com.ject.module.auth.infra.persistence.jpa.AppleRefreshTokenJpaRepository
import org.springframework.stereotype.Repository
import java.time.Clock

@Repository
class AppleRefreshTokenRepositoryAdapter(
    private val appleRefreshTokenJpaRepository: AppleRefreshTokenJpaRepository,
    private val tokenEncryptor: TokenEncryptor,
    private val clock: Clock,
) : AppleRefreshTokenRepository {
    override fun save(
        memberId: Long,
        refreshToken: String,
    ) {
        appleRefreshTokenJpaRepository.upsert(
            memberId = memberId,
            encryptedRefreshToken = tokenEncryptor.encrypt(refreshToken, associatedData(memberId)),
            savedAt = clock.instant(),
        )
    }

    override fun findRefreshToken(memberId: Long): String? {
        val token = appleRefreshTokenJpaRepository.findByMemberId(memberId) ?: return null
        return tokenEncryptor.decrypt(token.encryptedRefreshToken, associatedData(memberId))
    }

    override fun deleteByMemberId(memberId: Long): Boolean = appleRefreshTokenJpaRepository.deleteByMemberIdIfExists(memberId) == 1

    /** 암호문을 다른 회원의 행으로 옮겨도 복호화되지 않도록 회원 ID를 인증 데이터로 묶습니다. */
    private fun associatedData(memberId: Long): String = "apple_refresh_token:$memberId"
}
