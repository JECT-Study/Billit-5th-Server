package com.ject.module.auth.infra.persistence

import com.ject.module.auth.domain.AppleRefreshTokenRepository
import com.ject.module.auth.domain.StoredAppleRefreshToken
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppleRefreshTokenRepositoryAdapterTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var appleRefreshTokenRepository: AppleRefreshTokenRepository

    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM apple_refresh_token WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("같은 회원의 토큰을 동시에 저장해도 한 행만 남고 저장한 토큰 중 하나로 복호화된다") {
            // given
            val memberId = createAppleMember()
            val tokens = (1..8).map { "apple-refresh-token-$it" }
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(tokens.size)

            // when
            try {
                val futures =
                    tokens.map { token ->
                        executor.submit {
                            start.await()
                            appleRefreshTokenRepository.save(memberId, token)
                        }
                    }
                start.countDown()
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally {
                // 실패해도 남은 저장이 afterEach의 회원 삭제와 겹치지 않게 끝날 때까지 기다립니다.
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }

            // then
            val stored = appleRefreshTokenRepository.find(memberId).shouldBeInstanceOf<StoredAppleRefreshToken.Decrypted>()
            assertSoftly {
                countAppleRefreshTokens(memberId) shouldBe 1
                stored.refreshToken shouldBeIn tokens
            }
        }

        test("읽은 뒤 새 토큰으로 교체된 행은 이전 저장값으로 삭제하지 않는다") {
            // given
            val memberId = createAppleMember()
            appleRefreshTokenRepository.save(memberId, "apple-refresh-token-old")
            val old = checkNotNull(appleRefreshTokenRepository.find(memberId))
            appleRefreshTokenRepository.save(memberId, "apple-refresh-token-new")

            // when
            val deleted = appleRefreshTokenRepository.deleteIfUnchanged(memberId, old.encryptedRefreshToken)

            // then
            assertSoftly {
                deleted shouldBe false
                countAppleRefreshTokens(memberId) shouldBe 1
            }
        }

        test("읽은 저장값과 같으면 삭제한다") {
            // given
            val memberId = createAppleMember()
            appleRefreshTokenRepository.save(memberId, "apple-refresh-token")
            val stored = checkNotNull(appleRefreshTokenRepository.find(memberId))

            // when
            val deleted = appleRefreshTokenRepository.deleteIfUnchanged(memberId, stored.encryptedRefreshToken)

            // then
            assertSoftly {
                deleted shouldBe true
                countAppleRefreshTokens(memberId) shouldBe 0
            }
        }

        test("다른 회원의 행으로 옮긴 암호문은 예외 없이 복호화 불가로 읽는다") {
            // given
            val owner = createAppleMember()
            val other = createAppleMember()
            appleRefreshTokenRepository.save(owner, "apple-refresh-token")
            val movedCiphertext = checkNotNull(appleRefreshTokenRepository.find(owner)).encryptedRefreshToken
            jdbc.update(
                "INSERT INTO apple_refresh_token (member_id, encrypted_refresh_token, created_at, updated_at) VALUES (?, ?, now(), now())",
                other,
                movedCiphertext,
            )

            // when
            val stored = appleRefreshTokenRepository.find(other)

            // then
            stored.shouldBeInstanceOf<StoredAppleRefreshToken.Undecryptable>().encryptedRefreshToken shouldBe movedCiphertext
        }

        test("base64가 아니거나 nonce보다 짧은 저장값은 예외 없이 복호화 불가로 읽는다") {
            // given
            val memberIdsByCiphertext = listOf("not-base64!", "AAAA").associateWith { createAppleMember() }
            memberIdsByCiphertext.forEach { (ciphertext, memberId) ->
                jdbc.update(
                    "INSERT INTO apple_refresh_token (member_id, encrypted_refresh_token, created_at, updated_at) VALUES (?, ?, now(), now())",
                    memberId,
                    ciphertext,
                )
            }

            // when
            val results = memberIdsByCiphertext.values.map { appleRefreshTokenRepository.find(it) }

            // then
            results.map { it?.javaClass } shouldBe List(2) { StoredAppleRefreshToken.Undecryptable::class.java }
        }
    }

    private fun createAppleMember(): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) VALUES ('APPLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(id)
        return id
    }

    private fun countAppleRefreshTokens(memberId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM apple_refresh_token WHERE member_id = ?", Int::class.java, memberId))
}
