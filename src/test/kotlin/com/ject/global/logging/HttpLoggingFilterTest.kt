package com.ject.global.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import jakarta.servlet.FilterChain
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping

class HttpLoggingFilterTest :
    FunSpec({
        val logger = LoggerFactory.getLogger(HttpLoggingFilter::class.java) as Logger
        val appender =
            object : ListAppender<ILoggingEvent>() {
                override fun append(event: ILoggingEvent) {
                    event.prepareForDeferredProcessing()
                    super.append(event)
                }
            }

        beforeTest {
            MDC.clear()
            appender.list.clear()
            appender.start()
            logger.addAppender(appender)
        }

        afterTest {
            logger.detachAppender(appender)
            appender.stop()
            MDC.clear()
        }

        test("유효한 요청 ID를 응답과 로그에 포함하고 쿼리와 실제 경로 값은 제외한다") {
            // given
            val request = MockHttpServletRequest("GET", "/trips/private-value")
            request.addHeader("X-Request-Id", "request-123")
            request.queryString = "token=secret"
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/trips/{tripId}")
            val response = MockHttpServletResponse()
            var observedRequestId: String? = null
            val chain =
                FilterChain { _, _ ->
                    observedRequestId = MDC.get("requestId")
                    response.status = 201
                }

            // when
            HttpLoggingFilter().doFilter(request, response, chain)

            // then
            observedRequestId shouldBe "request-123"
            response.getHeader("X-Request-Id") shouldBe "request-123"
            val entry = appender.list.single()
            entry.mdcPropertyMap["requestId"] shouldBe "request-123"
            entry.keyValuePairs.associate { it.key to it.value }["uri"] shouldBe "/trips/{tripId}"
            entry.keyValuePairs.associate { it.key to it.value }["status"] shouldBe 201
            entry.formattedMessage.contains("secret") shouldBe false
            entry.formattedMessage.contains("private-value") shouldBe false
            MDC.get("requestId") shouldBe null
        }

        test("허용 길이를 넘는 요청 ID는 UUID로 교체한다") {
            // given
            val request = MockHttpServletRequest("GET", "/trips")
            request.addHeader("X-Request-Id", "x".repeat(129))
            val response = MockHttpServletResponse()

            // when
            HttpLoggingFilter().doFilter(request, response, FilterChain { _, _ -> })

            // then
            response.getHeader("X-Request-Id").shouldNotBeNull() shouldMatch Regex("[a-f0-9-]{36}")
            appender.list.single().mdcPropertyMap["requestId"] shouldBe response.getHeader("X-Request-Id")
            MDC.get("requestId") shouldBe null
        }

        test("처리되지 않은 예외는 실패 상태로 기록하고 요청 ID를 정리한다") {
            // given
            val request = MockHttpServletRequest("GET", "/trips")
            val response = MockHttpServletResponse()
            val failure = IllegalStateException("request failed")
            val chain = FilterChain { _, _ -> throw failure }

            // when
            val thrown =
                shouldThrow<IllegalStateException> {
                    HttpLoggingFilter().doFilter(request, response, chain)
                }

            // then
            thrown shouldBe failure
            appender.list
                .single()
                .keyValuePairs
                .associate { it.key to it.value }["status"] shouldBe 500
            MDC.get("requestId") shouldBe null
        }

        test("상태 확인 요청은 완료 로그에서 제외한다") {
            // given
            val request = MockHttpServletRequest("GET", "/actuator/health")
            val response = MockHttpServletResponse()

            // when
            HttpLoggingFilter().doFilter(request, response, FilterChain { _, _ -> })

            // then
            appender.list.size shouldBe 0
            MDC.get("requestId") shouldBe null
        }
    })
