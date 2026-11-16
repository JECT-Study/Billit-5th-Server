package com.ject.module.auth.usecase

import com.ject.global.error.BusinessException
import com.ject.global.error.CommonErrorCode
import com.ject.module.auth.implement.AuthTokenCreator
import com.ject.support.IntegrationTestSupport
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.util.UUID

class LogoutUseCaseTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var logoutUseCase: LogoutUseCase

    @Autowired
    private lateinit var refreshTokensUseCase: RefreshTokensUseCase

    @Autowired
    private lateinit var authTokenCreator: AuthTokenCreator

    @Autowired
    @Qualifier("memberJwtDecoder")
    private lateinit var memberJwtDecoder: JwtDecoder

    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("Access Token의 세션으로 로그아웃하면 그 세션만 종료하고 같은 회원의 다른 세션은 유지한다") {
            // given
            val memberId = createMember()
            val current = authTokenCreator.createMemberTokens(memberId)
            val other = authTokenCreator.createMemberTokens(memberId)

            // when
            logoutUseCase.logoutByAccessToken(sessionId(current.accessToken))

            // then
            assertSoftly {
                revokedAt(sessionId(current.accessToken)).shouldNotBeNull()
                revokedAt(sessionId(other.accessToken)).shouldBeNull()
                shouldThrow<JwtException> { memberJwtDecoder.decode(current.accessToken) }
                memberJwtDecoder.decode(other.accessToken).subject shouldBe memberId.toString()
            }
        }

        test("현재 Refresh Token으로 로그아웃하면 그 세션을 종료해 Access Token과 Refresh Token을 쓸 수 없게 한다") {
            // given
            val memberId = createMember()
            val current = authTokenCreator.createMemberTokens(memberId)

            // when
            logoutUseCase.logoutByRefreshToken(current.refreshToken)

            // then
            assertSoftly {
                revokedAt(sessionId(current.refreshToken)).shouldNotBeNull()
                shouldThrow<JwtException> { memberJwtDecoder.decode(current.accessToken) }
                shouldThrow<BusinessException> { refreshTokensUseCase.refresh(current.refreshToken) }
            }
        }

        test("이미 종료된 세션은 Access Token과 Refresh Token 모두 401 COMMON_UNAUTHORIZED로 실패한다") {
            // given
            val memberId = createMember()
            val current = authTokenCreator.createMemberTokens(memberId)
            logoutUseCase.logoutByAccessToken(sessionId(current.accessToken))

            // when
            val failures =
                listOf(
                    shouldThrow<BusinessException> { logoutUseCase.logoutByAccessToken(sessionId(current.accessToken)) },
                    shouldThrow<BusinessException> { logoutUseCase.logoutByRefreshToken(current.refreshToken) },
                )

            // then
            failures.map { it.errorCode } shouldBe List(2) { CommonErrorCode.UNAUTHORIZED }
        }

        test("회전 직후 재시도 허용 시간 안의 직전 Refresh Token으로도 로그아웃할 수 없고 세션은 유지된다") {
            // given
            val memberId = createMember()
            val issued = authTokenCreator.createMemberTokens(memberId)
            refreshTokensUseCase.refresh(issued.refreshToken)

            // when
            val failure = shouldThrow<BusinessException> { logoutUseCase.logoutByRefreshToken(issued.refreshToken) }

            // then
            failure.errorCode shouldBe CommonErrorCode.UNAUTHORIZED
            revokedAt(sessionId(issued.refreshToken)).shouldBeNull()
        }

        test("Refresh Token이 아닌 토큰이나 형식이 잘못된 토큰은 401 COMMON_UNAUTHORIZED로 실패한다") {
            // given
            val memberId = createMember()
            val tokens = listOf(authTokenCreator.createMemberTokens(memberId).accessToken, "invalid-token")

            // when
            val errorCodes = tokens.map { shouldThrow<BusinessException> { logoutUseCase.logoutByRefreshToken(it) }.errorCode }

            // then
            errorCodes shouldBe List(2) { CommonErrorCode.UNAUTHORIZED }
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

    private fun revokedAt(sessionId: UUID): Any? =
        jdbc.queryForObject("SELECT revoked_at FROM auth_session WHERE id = ?", Any::class.java, sessionId)
}
