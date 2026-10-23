package com.ject.module.auth.presentation

import com.ject.module.auth.infra.JwtAccessTokenCodec
import com.ject.module.auth.infra.JwtProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class JwtAuthenticationFilterTest :
    FunSpec({
        val now = Instant.parse("2026-10-23T00:00:00Z")
        val properties = JwtProperties("test-only-jwt-secret-at-least-32-bytes", "billit", Duration.ofHours(1))
        val codec = JwtAccessTokenCodec(properties, Clock.fixed(now, ZoneOffset.UTC))
        val mapper = JsonMapper.builder().build()
        val filter = JwtAuthenticationFilter(codec, ApiAuthenticationEntryPoint(mapper))

        beforeTest { SecurityContextHolder.clearContext() }
        afterTest { SecurityContextHolder.clearContext() }

        test("Bearer 토큰이 유효하면 인증된 사용자 ID를 다음 처리에 전달한다") {
            // given
            val request = MockHttpServletRequest("GET", "/private")
            request.addHeader("Authorization", "bEaReR " + codec.issue("user-123"))
            val response = MockHttpServletResponse()
            var principal: Any? = null
            var authenticated = false
            // when
            filter.doFilter(
                request,
                response,
                FilterChain { _, _ ->
                    principal =
                        SecurityContextHolder
                            .getContext()
                            .authentication
                            .shouldNotBeNull()
                            .principal
                    authenticated =
                        SecurityContextHolder
                            .getContext()
                            .authentication
                            .shouldNotBeNull()
                            .isAuthenticated
                },
            )
            // then
            principal shouldBe "user-123"
            authenticated shouldBe true
        }

        test("토큰이 없으면 인증 정보를 만들지 않고 다음 처리로 넘긴다") {
            // given
            val request = MockHttpServletRequest("GET", "/public")
            var continued = false
            // when
            filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> continued = true })
            // then
            continued shouldBe true
            SecurityContextHolder.getContext().authentication shouldBe null
        }

        val invalidTokens =
            mapOf(
                "잘못된 형식" to "invalid-token",
                "빈 토큰" to "",
                "만료된 토큰" to JwtAccessTokenCodec(properties, Clock.fixed(now.minusSeconds(3600), ZoneOffset.UTC)).issue("user-123"),
                "다른 서명" to
                    JwtAccessTokenCodec(
                        JwtProperties("other-test-secret-at-least-32-bytes", "billit", Duration.ofHours(1)),
                        Clock.fixed(now, ZoneOffset.UTC),
                    ).issue("user-123"),
            )
        invalidTokens.forEach { (condition, token) ->
            test("$condition 요청은 401 응답을 반환하고 다음 처리를 실행하지 않는다") {
                // given
                val request = MockHttpServletRequest("GET", "/private")
                request.addHeader("Authorization", "Bearer $token")
                val response = MockHttpServletResponse()
                var continued = false
                // when
                filter.doFilter(request, response, FilterChain { _, _ -> continued = true })
                // then
                response.status shouldBe 401
                mapper.readTree(response.contentAsString).get("code").asText() shouldBe "COMMON_UNAUTHORIZED"
                continued shouldBe false
                SecurityContextHolder.getContext().authentication shouldBe null
            }
        }

        test("후속 처리의 예외를 인증 실패로 바꾸지 않는다") {
            // given
            val request = MockHttpServletRequest("GET", "/private")
            request.addHeader("Authorization", "Bearer " + codec.issue("user-123"))
            val response = MockHttpServletResponse()
            // when
            val failure =
                shouldThrow<IllegalStateException> {
                    filter.doFilter(request, response, FilterChain { _, _ -> throw IllegalStateException("downstream failure") })
                }
            // then
            failure.message shouldBe "downstream failure"
            response.status shouldBe 200
        }
    })
