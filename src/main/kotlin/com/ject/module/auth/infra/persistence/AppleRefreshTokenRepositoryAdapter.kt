package com.ject.module.auth.infra.persistence

import com.ject.module.auth.domain.AppleRefreshTokenRepository
import com.ject.module.auth.domain.StoredAppleRefreshToken
import com.ject.module.auth.infra.TokenEncryptor
import com.ject.module.auth.infra.persistence.jpa.AppleRefreshTokenJpaRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Repository
import java.security.GeneralSecurityException
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

    override fun find(memberId: Long): StoredAppleRefreshToken? {
        val encrypted = appleRefreshTokenJpaRepository.findByMemberId(memberId)?.encryptedRefreshToken ?: return null
        return try {
            StoredAppleRefreshToken.Decrypted(tokenEncryptor.decrypt(encrypted, associatedData(memberId)), encrypted)
        } catch (exception: GeneralSecurityException) {
            undecryptable(memberId, encrypted, exception)
        } catch (exception: IllegalArgumentException) {
            undecryptable(memberId, encrypted, exception)
        }
    }

    override fun deleteIfUnchanged(
        memberId: Long,
        encryptedRefreshToken: String,
    ): Boolean = appleRefreshTokenJpaRepository.deleteByMemberIdAndEncryptedRefreshToken(memberId, encryptedRefreshToken) == 1

    /** 저장값과 키는 로그에 남기지 않고 예외 종류만 남깁니다. */
    private fun undecryptable(
        memberId: Long,
        encrypted: String,
        exception: Exception,
    ): StoredAppleRefreshToken.Undecryptable {
        log.error { "event=apple_refresh_token_undecryptable memberId=$memberId exceptionType=${exception.javaClass.name}" }
        return StoredAppleRefreshToken.Undecryptable(encrypted)
    }

    /** 암호문을 다른 회원의 행으로 옮겨도 복호화되지 않도록 회원 ID를 인증 데이터로 묶습니다. */
    private fun associatedData(memberId: Long): String = "apple_refresh_token:$memberId"

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
