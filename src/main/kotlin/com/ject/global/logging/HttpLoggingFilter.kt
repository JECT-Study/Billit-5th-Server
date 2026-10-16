package com.ject.global.logging

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID
import java.util.regex.Pattern

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class HttpLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        var requestId = request.getHeader(REQUEST_ID_HEADER)
        if (requestId == null || !VALID_REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString()
        }
        response.setHeader(REQUEST_ID_HEADER, requestId)
        val startedAt = System.nanoTime()
        var completed = false
        MDC.putCloseable(REQUEST_ID_MDC_KEY, requestId).use {
            try {
                filterChain.doFilter(request, response)
                completed = true
            } finally {
                val uri = request.requestURI
                val shouldLog = uri != "/actuator" && !uri.startsWith("/actuator/")
                if (shouldLog) {
                    val path = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: uri
                    val status = if (!completed && response.status < 400) 500 else response.status
                    val durationMillis = (System.nanoTime() - startedAt) / 1_000_000
                    log.atInfo {
                        message = "HTTP ${request.method} $path -> $status (${durationMillis}ms)"
                        payload =
                            mapOf(
                                "event" to "http_request",
                                "http_method" to request.method,
                                "uri" to path,
                                "status" to status,
                                "duration_ms" to durationMillis,
                            )
                    }
                }
            }
        }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val REQUEST_ID_MDC_KEY = "requestId"
        private val log = KotlinLogging.logger {}
        private val VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}")
    }
}
