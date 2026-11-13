package com.ject.module.auth.usecase

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.domain.SocialIdentity
import com.ject.module.auth.domain.SocialLoginResult
import com.ject.module.auth.usecase.port.SocialIdTokenPort
import com.ject.module.member.domain.AuthProvider
import com.ject.module.member.domain.MemberRepository
import com.ject.support.IntegrationTestSupport
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SocialLoginUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var socialLoginUseCase: SocialLoginUseCase

    @MockitoBean
    private lateinit var socialIdTokenPort: SocialIdTokenPort

    @MockitoSpyBean
    private lateinit var memberRepository: MemberRepository

    private val providerUserIds = mutableListOf<String>()

    init {
        afterEach {
            providerUserIds.forEach { providerUserId ->
                jdbc.update(
                    "DELETE FROM auth_session WHERE member_id IN (SELECT id FROM member WHERE social_provider_user_id = ?)",
                    providerUserId,
                )
                jdbc.update("DELETE FROM member WHERE social_provider_user_id = ?", providerUserId)
            }
            providerUserIds.clear()
        }

        test("처음 로그인한 소셜 계정은 가입 중 회원을 만들고 세션 없이 가입 전용 토큰을 반환한다") {
            // given
            val providerUserId = givenVerifiedIdToken(AuthProvider.GOOGLE, "google-id-token")

            // when
            val result = socialLoginUseCase.login(AuthProvider.GOOGLE, "google-id-token")

            // then
            val registrationRequired = result.shouldBeInstanceOf<SocialLoginResult.RegistrationRequired>()
            val members = findMembers(AuthProvider.GOOGLE, providerUserId)
            assertSoftly {
                members.map { it["status"] } shouldBe listOf("PENDING")
                claims(registrationRequired.registrationToken) shouldBe
                    mapOf("sub" to members.single()["id"].toString(), "token_type" to "REGISTRATION")
                countSessions(members.single()["id"] as Long) shouldBe 0
            }
        }

        test("가입을 마치지 않은 회원이 다시 로그인하면 회원을 새로 만들지 않고 가입 전용 토큰을 반환한다") {
            // given
            val providerUserId = givenVerifiedIdToken(AuthProvider.KAKAO, "kakao-id-token")
            val memberId = insertMember(AuthProvider.KAKAO, providerUserId, "PENDING")

            // when
            val result = socialLoginUseCase.login(AuthProvider.KAKAO, "kakao-id-token")

            // then
            val registrationRequired = result.shouldBeInstanceOf<SocialLoginResult.RegistrationRequired>()
            assertSoftly {
                findMembers(AuthProvider.KAKAO, providerUserId).size shouldBe 1
                claims(registrationRequired.registrationToken)["sub"] shouldBe memberId.toString()
                countSessions(memberId) shouldBe 0
            }
        }

        test("처음 로그인할 때 받은 표시 이름을 가입 중 회원에 저장한다") {
            // given
            val providerUserId = givenVerifiedIdToken(AuthProvider.GOOGLE, "google-id-token", displayName = "홍길동")

            // when
            socialLoginUseCase.login(AuthProvider.GOOGLE, "google-id-token")

            // then
            findMembers(AuthProvider.GOOGLE, providerUserId).map { it["social_display_name"] } shouldBe listOf("홍길동")
        }

        test("표시 이름이 없는 가입 중 회원이 다시 로그인하며 표시 이름을 받으면 채운다") {
            // given
            val providerUserId = givenVerifiedIdToken(AuthProvider.KAKAO, "kakao-id-token", displayName = "길동")
            insertMember(AuthProvider.KAKAO, providerUserId, "PENDING")

            // when
            socialLoginUseCase.login(AuthProvider.KAKAO, "kakao-id-token")

            // then
            findMembers(AuthProvider.KAKAO, providerUserId).map { it["social_display_name"] } shouldBe listOf("길동")
        }

        test("가입 중 회원에게 저장된 표시 이름은 다시 로그인해 다른 값을 받아도 덮어쓰지 않는다") {
            // given
            val providerUserId = givenVerifiedIdToken(AuthProvider.GOOGLE, "google-id-token", displayName = "새 이름")
            insertMember(AuthProvider.GOOGLE, providerUserId, "PENDING", socialDisplayName = "처음 이름")

            // when
            socialLoginUseCase.login(AuthProvider.GOOGLE, "google-id-token")

            // then
            findMembers(AuthProvider.GOOGLE, providerUserId).map { it["social_display_name"] } shouldBe listOf("처음 이름")
        }

        test("가입을 마친 회원은 새 세션을 만들고 회원용 Access Token과 Refresh Token을 반환한다") {
            // given
            val providerUserId = givenVerifiedIdToken(AuthProvider.GOOGLE, "google-id-token")
            val memberId = insertMember(AuthProvider.GOOGLE, providerUserId, "ACTIVE")

            // when
            val result = socialLoginUseCase.login(AuthProvider.GOOGLE, "google-id-token")

            // then
            val authenticated = result.shouldBeInstanceOf<SocialLoginResult.Authenticated>()
            assertSoftly {
                claims(authenticated.tokens.accessToken) shouldBe mapOf("sub" to memberId.toString(), "token_type" to "ACCESS")
                claims(authenticated.tokens.refreshToken) shouldBe mapOf("sub" to memberId.toString(), "token_type" to "REFRESH")
                countSessions(memberId) shouldBe 1
            }
        }

        test("같은 외부 사용자 ID라도 제공자가 다르면 다른 회원으로 처리한다") {
            // given
            val providerUserId = givenVerifiedIdToken(AuthProvider.KAKAO, "kakao-id-token")
            insertMember(AuthProvider.GOOGLE, providerUserId, "ACTIVE")

            // when
            val result = socialLoginUseCase.login(AuthProvider.KAKAO, "kakao-id-token")

            // then
            result.shouldBeInstanceOf<SocialLoginResult.RegistrationRequired>()
            findMembers(AuthProvider.KAKAO, providerUserId).map { it["status"] } shouldBe listOf("PENDING")
        }

        test("외부 사용자 ID가 남아 있는 탈퇴 회원은 401 AUTH_SOCIAL_AUTHENTICATION_FAILED로 거절하고 회원과 세션을 만들지 않는다") {
            // given
            val providerUserId = givenVerifiedIdToken(AuthProvider.GOOGLE, "google-id-token")
            val memberId = insertMember(AuthProvider.GOOGLE, providerUserId, "WITHDRAWN")

            // when
            val failure = shouldThrow<BusinessException> { socialLoginUseCase.login(AuthProvider.GOOGLE, "google-id-token") }

            // then
            assertSoftly {
                failure.errorCode shouldBe AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED
                findMembers(AuthProvider.GOOGLE, providerUserId).size shouldBe 1
                countSessions(memberId) shouldBe 0
            }
        }

        test("ID 토큰 검증에 실패하면 회원을 조회하거나 만들지 않는다") {
            // given
            `when`(socialIdTokenPort.verify(AuthProvider.GOOGLE, "invalid-id-token"))
                .thenThrow(BusinessException(AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED))

            // when
            val failure = shouldThrow<BusinessException> { socialLoginUseCase.login(AuthProvider.GOOGLE, "invalid-id-token") }

            // then
            failure.errorCode shouldBe AuthErrorCode.SOCIAL_AUTHENTICATION_FAILED
            verifyNoInteractions(memberRepository)
        }

        test("같은 소셜 계정의 최초 로그인이 동시에 들어와도 회원은 한 명만 만들고 두 요청 모두 그 회원의 가입 전용 토큰을 받는다") {
            // given
            val providerUserId = givenVerifiedIdToken(AuthProvider.GOOGLE, "google-id-token")
            // 두 요청이 모두 회원 없음을 확인한 뒤에 저장하도록 첫 조회 두 번을 맞춥니다.
            val barrier = CyclicBarrier(2)
            val firstLookups = AtomicInteger()
            doAnswer { invocation ->
                val result = invocation.callRealMethod()
                if (firstLookups.incrementAndGet() <= 2) {
                    barrier.await(10, TimeUnit.SECONDS)
                }
                result
            }.`when`(memberRepository).findBySocialAccount(AuthProvider.GOOGLE, providerUserId)
            val executor = Executors.newFixedThreadPool(2)

            // when
            val results =
                try {
                    List(2) { executor.submit<SocialLoginResult> { socialLoginUseCase.login(AuthProvider.GOOGLE, "google-id-token") } }
                        .map { it.get(30, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }

            // then
            val members = findMembers(AuthProvider.GOOGLE, providerUserId)
            val subjects =
                results.map { claims(it.shouldBeInstanceOf<SocialLoginResult.RegistrationRequired>().registrationToken)["sub"] }
            assertSoftly {
                members.size shouldBe 1
                subjects shouldBe List(2) { members.single()["id"].toString() }
            }
        }
    }

    private fun givenVerifiedIdToken(
        provider: AuthProvider,
        idToken: String,
        displayName: String? = null,
    ): String {
        val providerUserId = "test-${UUID.randomUUID()}"
        providerUserIds.add(providerUserId)
        `when`(socialIdTokenPort.verify(provider, idToken)).thenReturn(SocialIdentity(subject = providerUserId, displayName = displayName))
        return providerUserId
    }

    private fun insertMember(
        provider: AuthProvider,
        providerUserId: String,
        status: String,
        socialDisplayName: String? = null,
    ): Long =
        checkNotNull(
            jdbc.queryForObject(
                "INSERT INTO member (social_provider, social_provider_user_id, status, social_display_name, created_at, updated_at) " +
                    "VALUES (?::auth_provider, ?, ?::member_status, ?, now(), now()) RETURNING id",
                Long::class.java,
                provider.name,
                providerUserId,
                status,
                socialDisplayName,
            ),
        )

    private fun findMembers(
        provider: AuthProvider,
        providerUserId: String,
    ): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT id, status::text AS status, social_display_name FROM member WHERE social_provider = ?::auth_provider AND social_provider_user_id = ?",
            provider.name,
            providerUserId,
        )

    private fun countSessions(memberId: Long): Int =
        checkNotNull(jdbc.queryForObject("SELECT count(*) FROM auth_session WHERE member_id = ?", Int::class.java, memberId))

    private fun claims(token: String): Map<String, Any?> {
        val claims = SignedJWT.parse(token).jwtClaimsSet
        return mapOf("sub" to claims.subject, "token_type" to claims.getStringClaim("token_type"))
    }
}
