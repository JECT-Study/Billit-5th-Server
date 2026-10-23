package com.ject.module.auth.presentation

import com.ject.module.auth.infra.JwtAccessTokenCodec
import com.nimbusds.jose.proc.BadJOSEException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import java.text.ParseException

class JwtAuthenticationFilter(
    private val jwtAccessTokenCodec: JwtAccessTokenCodec,
    private val authenticationEntryPoint: ApiAuthenticationEntryPoint,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authorization = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (authorization == null || !authorization.startsWith("Bearer ", ignoreCase = true)) {
            filterChain.doFilter(request, response)
            return
        }

        val userId =
            try {
                jwtAccessTokenCodec.verify(authorization.substring(7))
            } catch (exception: ParseException) {
                reject(request, response, exception)
                return
            } catch (exception: BadJOSEException) {
                reject(request, response, exception)
                return
            }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = UsernamePasswordAuthenticationToken.authenticated(userId, null, emptyList())
        SecurityContextHolder.setContext(context)
        filterChain.doFilter(request, response)
    }

    private fun reject(
        request: HttpServletRequest,
        response: HttpServletResponse,
        cause: Exception,
    ) {
        SecurityContextHolder.clearContext()
        authenticationEntryPoint.commence(request, response, BadCredentialsException("Invalid access token", cause))
    }
}
