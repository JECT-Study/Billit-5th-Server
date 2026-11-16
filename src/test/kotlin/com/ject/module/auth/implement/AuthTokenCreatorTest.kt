package com.ject.module.auth.implement

import com.ject.module.auth.domain.AuthSessionRepository
import com.ject.support.IntegrationTestSupport
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.oauth2.jwt.JwtEncoder
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

class AuthTokenCreatorTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var jwtEncoder: JwtEncoder

    @Autowired
    private lateinit var authSessionRepository: AuthSessionRepository

    private val memberIds = mutableListOf<Long>()

    init {
        afterEach {
            memberIds.forEach { id ->
                jdbc.update("DELETE FROM auth_session WHERE member_id = ?", id)
                jdbc.update("DELETE FROM member WHERE id = ?", id)
            }
            memberIds.clear()
        }

        test("회원 토큰을 발급하면 세션을 만들고 Refresh Token은 원문 대신 SHA-256 해시로 저장한다") {
            // given
            val now = Instant.parse("2026-11-13T00:00:00Z")
            val memberId = createMember()
            val creator = AuthTokenCreator(jwtEncoder, authSessionRepository, RefreshTokenHasher(), Clock.fixed(now, ZoneOffset.UTC))

            // when
            val tokens = creator.createMemberTokens(memberId)

            // then
            val sessionId = UUID.fromString(SignedJWT.parse(tokens.accessToken).jwtClaimsSet.getStringClaim("sid"))
            val session = jdbc.queryForMap("SELECT * FROM auth_session WHERE id = ?", sessionId)
            assertSoftly {
                session["member_id"] shouldBe memberId
                session["refresh_token_hash"] shouldBe sha256Hex(tokens.refreshToken)
                session["refresh_token_hash"] shouldNotBe tokens.refreshToken
                (session["refresh_token_expires_at"] as Timestamp).toInstant() shouldBe Instant.parse("2026-12-13T00:00:00Z")
                session["revoked_at"] shouldBe null
                session["previous_refresh_token_hash"] shouldBe null
                session["refresh_token_rotated_at"] shouldBe null
                session["version"] shouldBe 0L
                SignedJWT.parse(tokens.refreshToken).jwtClaimsSet.getStringClaim("sid") shouldBe sessionId.toString()
            }
        }

        test("회원용 Access Token은 15분, Refresh Token은 30일 동안 유효하고 토큰 종류와 회원을 담는다") {
            // given
            val now = Instant.parse("2026-11-13T00:00:00Z")
            val memberId = createMember()
            val creator = AuthTokenCreator(jwtEncoder, authSessionRepository, RefreshTokenHasher(), Clock.fixed(now, ZoneOffset.UTC))

            // when
            val tokens = creator.createMemberTokens(memberId)

            // then
            val access = SignedJWT.parse(tokens.accessToken)
            val refresh = SignedJWT.parse(tokens.refreshToken)
            assertSoftly {
                access.header.algorithm.name shouldBe "HS512"
                access.jwtClaimsSet.subject shouldBe memberId.toString()
                access.jwtClaimsSet.getStringClaim("token_type") shouldBe "ACCESS"
                access.jwtClaimsSet.issueTime.toInstant() shouldBe now
                access.jwtClaimsSet.expirationTime.toInstant() shouldBe Instant.parse("2026-11-13T00:15:00Z")
                refresh.jwtClaimsSet.subject shouldBe memberId.toString()
                refresh.jwtClaimsSet.getStringClaim("token_type") shouldBe "REFRESH"
                refresh.jwtClaimsSet.expirationTime.toInstant() shouldBe Instant.parse("2026-12-13T00:00:00Z")
            }
        }

        test("가입 전용 토큰은 30분 동안 유효하고 세션을 만들지 않는다") {
            // given
            val now = Instant.parse("2026-11-13T00:00:00Z")
            val memberId = createMember()
            val creator = AuthTokenCreator(jwtEncoder, authSessionRepository, RefreshTokenHasher(), Clock.fixed(now, ZoneOffset.UTC))

            // when
            val token = creator.createRegistrationToken(memberId)

            // then
            val claims = SignedJWT.parse(token).jwtClaimsSet
            assertSoftly {
                claims.subject shouldBe memberId.toString()
                claims.getStringClaim("token_type") shouldBe "REGISTRATION"
                claims.getStringClaim("sid") shouldBe null
                claims.expirationTime.toInstant() shouldBe Instant.parse("2026-11-13T00:30:00Z")
                jdbc.queryForObject("SELECT COUNT(*) FROM auth_session WHERE member_id = ?", Int::class.java, memberId) shouldBe 0
            }
        }
    }

    private fun createMember(): Long {
        val id =
            checkNotNull(
                jdbc.queryForObject(
                    "INSERT INTO member (social_provider, status, created_at, updated_at) " +
                        "VALUES ('GOOGLE', 'ACTIVE', now(), now()) RETURNING id",
                    Long::class.java,
                ),
            )
        memberIds.add(id)
        return id
    }

    private fun sha256Hex(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
}
