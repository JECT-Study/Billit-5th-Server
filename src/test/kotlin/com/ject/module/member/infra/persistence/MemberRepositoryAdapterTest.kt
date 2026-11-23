package com.ject.module.member.infra.persistence

import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.MemberRepository
import com.ject.support.IntegrationTestSupport
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException

class MemberRepositoryAdapterTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var memberRepository: MemberRepository

    private val providerUserIds = mutableListOf<String>()

    init {
        afterEach {
            providerUserIds.forEach { jdbc.update("DELETE FROM member WHERE social_provider_user_id = ?", it) }
            providerUserIds.clear()
        }

        test("가입 중 회원 저장이 소셜 계정 중복이 아닌 무결성 오류로 실패하면 null 대신 오류를 그대로 던진다") {
            // given
            val tooLongProviderUserId = "x".repeat(256)
            providerUserIds.add(tooLongProviderUserId)

            // when
            val failure =
                shouldThrow<DataIntegrityViolationException> {
                    memberRepository.createPendingMember(AuthProvider.GOOGLE, tooLongProviderUserId)
                }

            // then
            assertSoftly {
                failure.message.orEmpty().contains("uq_member_social_provider_social_provider_user_id") shouldBe false
                countMembers(tooLongProviderUserId) shouldBe 0
            }
        }
    }

    private fun countMembers(providerUserId: String): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM member WHERE social_provider_user_id = ?", Int::class.java, providerUserId))
}
