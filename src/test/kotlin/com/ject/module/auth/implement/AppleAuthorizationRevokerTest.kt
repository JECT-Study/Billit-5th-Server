package com.ject.module.auth.implement

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AppleRefreshTokenRepository
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.usecase.port.AppleAuthorizationPort
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean

class AppleAuthorizationRevokerTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var appleAuthorizationRevoker: AppleAuthorizationRevoker

    @Autowired
    private lateinit var appleRefreshTokenRepository: AppleRefreshTokenRepository

    @MockitoBean
    private lateinit var appleAuthorizationPort: AppleAuthorizationPort

    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM apple_refresh_token WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("저장된 애플 Refresh Token 원문으로 연결 해제를 요청하고 성공하면 저장된 토큰을 삭제한다") {
            // given
            val memberId = createAppleMember()
            appleRefreshTokenRepository.save(memberId, "apple-refresh-token")

            // when
            val revoked = appleAuthorizationRevoker.revoke(memberId)

            // then
            assertSoftly {
                revoked shouldBe true
                verify(appleAuthorizationPort).revokeRefreshToken("apple-refresh-token")
                countAppleRefreshTokens(memberId) shouldBe 0
            }
        }

        test("저장된 애플 Refresh Token이 없으면 애플을 호출하지 않고 false를 반환한다") {
            // given
            val memberId = createAppleMember()

            // when
            val revoked = appleAuthorizationRevoker.revoke(memberId)

            // then
            revoked shouldBe false
            verifyNoInteractions(appleAuthorizationPort)
        }

        test("연결 해제 요청이 실패하면 같은 오류로 실패하고 저장된 토큰을 남긴다") {
            // given
            val memberId = createAppleMember()
            appleRefreshTokenRepository.save(memberId, "apple-refresh-token")
            doThrow(BusinessException(AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE))
                .`when`(appleAuthorizationPort)
                .revokeRefreshToken("apple-refresh-token")

            // when
            val failure = shouldThrow<BusinessException> { appleAuthorizationRevoker.revoke(memberId) }

            // then
            failure.errorCode shouldBe AuthErrorCode.SOCIAL_PROVIDER_UNAVAILABLE
            countAppleRefreshTokens(memberId) shouldBe 1
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
