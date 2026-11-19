package com.ject.module.auth.usecase

import com.ject.global.error.BusinessException
import com.ject.module.auth.domain.AuthErrorCode
import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.module.auth.domain.AuthTokenResult
import com.ject.module.auth.domain.RefreshTokenRetryPolicy
import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.module.auth.implement.LoginHistoryCreator
import com.ject.module.auth.implement.RefreshTokenHasher
import com.ject.module.auth.implement.RefreshTokenReader
import com.ject.support.IntegrationTestSupport
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RefreshTokensUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var refreshTokenReader: RefreshTokenReader

    @Autowired
    private lateinit var refreshTokenRetryPolicy: RefreshTokenRetryPolicy

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    @Autowired
    private lateinit var jwtEncoder: JwtEncoder

    @Autowired
    private lateinit var loginHistoryCreator: LoginHistoryCreator

    @Autowired
    @Qualifier("memberJwtDecoder")
    private lateinit var memberJwtDecoder: JwtDecoder

    @MockitoSpyBean
    private lateinit var authSessionRepository: AuthSessionRepository

    private val refreshTokenHasher = RefreshTokenHasher()

    /** 갱신과 재시도 허용 시간 판단의 기준 시각입니다. 테스트 실행이 늦어져도 유예 시간 안팎 판정이 바뀌지 않게 고정합니다. */
    private val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)

    private val refreshTokensUseCase by lazy {
        RefreshTokensUseCase(
            refreshTokenReader,
            authSessionRepository,
            refreshTokenHasher,
            authTokenCreator,
            refreshTokenRetryPolicy,
            loginHistoryCreator,
            clock,
        )
    }
    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM login_history WHERE member_id = ?", id)
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("현재 Refresh Token으로 갱신하면 같은 세션의 새 토큰 쌍을 발급하고 기존 토큰을 직전 토큰으로 옮긴다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)
            val sessionId = sessionId(issued.refreshToken)

            // when
            val refreshed = refreshTokensUseCase.refresh(issued.refreshToken)

            // then
            val session = findSession(sessionId)
            assertSoftly {
                sessionId(refreshed.accessToken) shouldBe sessionId
                sessionId(refreshed.refreshToken) shouldBe sessionId
                refreshed.refreshToken shouldNotBe issued.refreshToken
                session["refresh_token_hash"] shouldBe refreshTokenHasher.hash(refreshed.refreshToken)
                session["previous_refresh_token_hash"] shouldBe refreshTokenHasher.hash(issued.refreshToken)
                session["refresh_token_rotated_at"].shouldNotBeNull()
                session["revoked_at"].shouldBeNull()
                session["version"] shouldBe 1L
            }
        }

        test("갱신으로 받은 Refresh Token으로 다시 갱신할 수 있다") {
            // given
            val memberId = createMember()
            val first = refreshTokensUseCase.refresh(authTokenCreator.createMemberTokens(memberId).refreshToken)

            // when
            val second = refreshTokensUseCase.refresh(first.refreshToken)

            // then
            findSession(sessionId(second.refreshToken))["refresh_token_hash"] shouldBe refreshTokenHasher.hash(second.refreshToken)
        }

        test("갱신에 성공하면 갱신 시각으로 TOKEN_REFRESH 로그인 기록을 남긴다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)

            // when
            refreshTokensUseCase.refresh(issued.refreshToken)

            // then
            val history =
                jdbc.queryForMap(
                    "SELECT login_type::text AS login_type, occurred_at FROM login_history WHERE member_id = ? AND login_type = 'TOKEN_REFRESH'",
                    memberId,
                )
            assertSoftly {
                history["login_type"] shouldBe "TOKEN_REFRESH"
                (history["occurred_at"] as Timestamp).toInstant() shouldBe clock.instant()
            }
        }

        test("갱신에 실패하면 TOKEN_REFRESH 로그인 기록을 남기지 않는다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)
            refreshTokensUseCase.refresh(issued.refreshToken)
            moveRotatedAt(sessionId(issued.refreshToken), Duration.ofSeconds(31))

            // when
            shouldThrow<BusinessException> { refreshTokensUseCase.refresh(issued.refreshToken) }

            // then
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_history WHERE member_id = ? AND login_type = 'TOKEN_REFRESH'",
                Int::class.java,
                memberId,
            ) shouldBe 1
        }

        test("유예 시간이 지난 직전 Refresh Token이 다시 오면 401로 거절하고 세션을 종료해 최신 토큰도 쓸 수 없게 한다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)
            val latest = refreshTokensUseCase.refresh(issued.refreshToken)
            moveRotatedAt(sessionId(issued.refreshToken), Duration.ofSeconds(31))

            // when
            val reuseFailure = shouldThrow<BusinessException> { refreshTokensUseCase.refresh(issued.refreshToken) }

            // then
            val latestFailure = shouldThrow<BusinessException> { refreshTokensUseCase.refresh(latest.refreshToken) }
            assertSoftly {
                reuseFailure.errorCode shouldBe AuthErrorCode.REFRESH_TOKEN_INVALID
                latestFailure.errorCode shouldBe AuthErrorCode.REFRESH_TOKEN_INVALID
                findSession(sessionId(issued.refreshToken))["revoked_at"].shouldNotBeNull()
                shouldThrow<JwtException> { memberJwtDecoder.decode(latest.accessToken) }
            }
        }

        test("두 번 이상 전에 회전한 Refresh Token은 유예 시간 안이어도 재사용으로 보고 세션을 종료한다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)
            val first = refreshTokensUseCase.refresh(issued.refreshToken)
            refreshTokensUseCase.refresh(first.refreshToken)

            // when
            val failure = shouldThrow<BusinessException> { refreshTokensUseCase.refresh(issued.refreshToken) }

            // then
            failure.errorCode shouldBe AuthErrorCode.REFRESH_TOKEN_INVALID
            findSession(sessionId(issued.refreshToken))["revoked_at"].shouldNotBeNull()
        }

        test("회전 후 유예 시간 안에 직전 Refresh Token으로 재시도하면 한 번은 새 토큰 쌍을 발급한다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)
            refreshTokensUseCase.refresh(issued.refreshToken)

            // when
            val retried = refreshTokensUseCase.refresh(issued.refreshToken)

            // then
            val session = findSession(sessionId(issued.refreshToken))
            assertSoftly {
                session["refresh_token_hash"] shouldBe refreshTokenHasher.hash(retried.refreshToken)
                session["revoked_at"].shouldBeNull()
                memberJwtDecoder.decode(retried.accessToken).subject shouldBe memberId.toString()
            }
        }

        test("유예 시간 안이라도 같은 직전 Refresh Token의 두 번째 재시도는 재사용으로 보고 세션을 종료한다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)
            refreshTokensUseCase.refresh(issued.refreshToken)
            val retried = refreshTokensUseCase.refresh(issued.refreshToken)

            // when
            val failure = shouldThrow<BusinessException> { refreshTokensUseCase.refresh(issued.refreshToken) }

            // then
            assertSoftly {
                failure.errorCode shouldBe AuthErrorCode.REFRESH_TOKEN_INVALID
                findSession(sessionId(issued.refreshToken))["revoked_at"].shouldNotBeNull()
                shouldThrow<BusinessException> { refreshTokensUseCase.refresh(retried.refreshToken) }
            }
        }

        test("한 세션에서 재사용을 감지해 종료해도 같은 회원의 다른 세션은 갱신할 수 있다") {
            // given
            val memberId = createMember()
            val reusedSession = authTokenCreator.createMemberTokens(memberId)
            val otherSession = authTokenCreator.createMemberTokens(memberId)
            refreshTokensUseCase.refresh(reusedSession.refreshToken)
            moveRotatedAt(sessionId(reusedSession.refreshToken), Duration.ofSeconds(31))
            shouldThrow<BusinessException> { refreshTokensUseCase.refresh(reusedSession.refreshToken) }

            // when
            val refreshed = refreshTokensUseCase.refresh(otherSession.refreshToken)

            // then
            assertSoftly {
                sessionId(refreshed.refreshToken) shouldBe sessionId(otherSession.refreshToken)
                findSession(sessionId(otherSession.refreshToken))["revoked_at"].shouldBeNull()
                memberJwtDecoder.decode(otherSession.accessToken).subject shouldBe memberId.toString()
            }
        }

        test("같은 Refresh Token으로 동시에 갱신하면 한 요청만 성공하고 나머지는 401이며 세션은 유지한다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)
            val sessionId = sessionId(issued.refreshToken)
            // 두 요청이 모두 같은 버전의 세션을 읽은 뒤에 회전하도록 맞춥니다.
            val barrier = CyclicBarrier(2)
            val lookups = AtomicInteger()
            doAnswer { invocation ->
                val result = invocation.callRealMethod()
                if (lookups.incrementAndGet() <= 2) {
                    barrier.await(10, TimeUnit.SECONDS)
                }
                result
            }.`when`(authSessionRepository).findById(sessionId)
            val executor = Executors.newFixedThreadPool(2)

            // when
            val outcomes =
                try {
                    List(2) {
                        executor.submit<Any> {
                            try {
                                refreshTokensUseCase.refresh(issued.refreshToken)
                            } catch (exception: BusinessException) {
                                exception.errorCode
                            }
                        }
                    }.map { it.get(30, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                }

            // then
            val session = findSession(sessionId)
            assertSoftly {
                outcomes.map { if (it is AuthTokenResult) "SUCCESS" else it } shouldContainExactlyInAnyOrder
                    listOf("SUCCESS", AuthErrorCode.REFRESH_TOKEN_INVALID)
                session["version"] shouldBe 1L
                session["revoked_at"].shouldBeNull()
            }
        }

        test("만료된 Refresh Token은 401로 거절하고 세션은 종료하지 않는다") {
            // given
            val memberId = createMember()
            val expired = issuedBefore(Duration.ofDays(31)).createMemberTokens(memberId)

            // when
            val failure = shouldThrow<BusinessException> { refreshTokensUseCase.refresh(expired.refreshToken) }

            // then
            failure.errorCode shouldBe AuthErrorCode.REFRESH_TOKEN_INVALID
            findSession(sessionId(expired.refreshToken))["revoked_at"].shouldBeNull()
        }

        test("Access Token이나 가입 전용 토큰을 Refresh Token으로 보내면 401로 거절한다") {
            // given
            val memberId = createMember()
            val tokens =
                listOf(authTokenCreator.createMemberTokens(memberId).accessToken, authTokenCreator.createRegistrationToken(memberId))

            // when
            val errorCodes = tokens.map { shouldThrow<BusinessException> { refreshTokensUseCase.refresh(it) }.errorCode }

            // then
            errorCodes shouldBe List(2) { AuthErrorCode.REFRESH_TOKEN_INVALID }
        }

        test("종료된 세션의 Refresh Token은 401로 거절한다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)
            jdbc.update("UPDATE auth_session SET revoked_at = now() WHERE id = ?", sessionId(issued.refreshToken))

            // when
            val failure = shouldThrow<BusinessException> { refreshTokensUseCase.refresh(issued.refreshToken) }

            // then
            failure.errorCode shouldBe AuthErrorCode.REFRESH_TOKEN_INVALID
        }
    }

    private fun createMember(): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(id)
        return id
    }

    private fun sessionId(token: String): UUID = UUID.fromString(SignedJWT.parse(token).jwtClaimsSet.getStringClaim("sid"))

    private fun findSession(sessionId: UUID): Map<String, Any?> = jdbc.queryForMap("SELECT * FROM auth_session WHERE id = ?", sessionId)

    /** 마지막 회전 시각을 [elapsed]만큼 과거로 옮겨 유예 시간이 지난 상황을 만듭니다. */
    private fun moveRotatedAt(
        sessionId: UUID,
        elapsed: Duration,
    ) {
        jdbc.update(
            "UPDATE auth_session SET refresh_token_rotated_at = ? WHERE id = ?",
            Timestamp.from(clock.instant().minus(elapsed)),
            sessionId,
        )
    }

    private fun issuedBefore(duration: Duration): AuthTokenCreator =
        AuthTokenCreator(
            jwtEncoder,
            authSessionRepository,
            refreshTokenHasher,
            loginHistoryCreator,
            Clock.fixed(clock.instant().minus(duration), ZoneOffset.UTC),
        )
}
