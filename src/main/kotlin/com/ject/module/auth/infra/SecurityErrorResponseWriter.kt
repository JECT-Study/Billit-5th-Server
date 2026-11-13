package com.ject.module.auth.infra

import com.ject.global.error.CommonErrorCode
import com.ject.global.error.ErrorCode
import com.ject.global.error.ErrorResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * 인증 실패와 권한 부족을 공통 오류 형식으로 응답합니다. `WWW-Authenticate` 헤더는 Spring Security 기본 처리를 따릅니다.
 *
 * 세션 조회 실패처럼 인증을 판단하지 못한 장애([AuthenticationServiceException])는 401 대신 503으로 응답합니다.
 */
@Component
class SecurityErrorResponseWriter(
    private val jsonMapper: JsonMapper,
) : AuthenticationEntryPoint,
    AccessDeniedHandler {
    private val bearerTokenAuthenticationEntryPoint = BearerTokenAuthenticationEntryPoint()
    private val bearerTokenAccessDeniedHandler = BearerTokenAccessDeniedHandler()

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        if (authException is AuthenticationServiceException) {
            log.error(authException) { "event=authentication_unavailable" }
            write(response, CommonErrorCode.SERVICE_UNAVAILABLE)
            return
        }
        bearerTokenAuthenticationEntryPoint.commence(request, response, authException)
        write(response, CommonErrorCode.UNAUTHORIZED)
    }

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        bearerTokenAccessDeniedHandler.handle(request, response, accessDeniedException)
        write(response, CommonErrorCode.FORBIDDEN)
    }

    private fun write(
        response: HttpServletResponse,
        errorCode: ErrorCode,
    ) {
        response.status = errorCode.status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        jsonMapper.writeValue(response.outputStream, ErrorResponse.from(errorCode))
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
