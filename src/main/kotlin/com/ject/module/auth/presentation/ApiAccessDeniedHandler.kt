package com.ject.module.auth.presentation

import com.ject.global.error.CommonErrorCode
import com.ject.global.error.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class ApiAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AccessDeniedException,
    ) {
        response.status = CommonErrorCode.FORBIDDEN.status
        response.contentType = "application/json; charset=UTF-8"
        objectMapper.writeValue(response.writer, ErrorResponse.from(CommonErrorCode.FORBIDDEN))
    }
}
