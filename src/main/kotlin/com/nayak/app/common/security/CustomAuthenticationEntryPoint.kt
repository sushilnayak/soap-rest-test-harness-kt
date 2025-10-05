package com.nayak.app.common.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.logging.MdcHelper
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.security.Principal

@Component
class CustomAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper
) : ServerAuthenticationEntryPoint {
    private val logger = LoggerFactory.getLogger(CustomAuthenticationEntryPoint::class.java)
    override fun commence(
        exchange: ServerWebExchange,
        ex: AuthenticationException
    ): Mono<Void> {
        val correlationId = exchange.attributes[MdcHelper.CORRELATION_ID_KEY] as? String
            ?: exchange.request.headers.getFirst("X-Correlation-ID")
            ?: "UNKNOWN"

        MDC.put(MdcHelper.CORRELATION_ID_KEY, correlationId)

        try {
            exchange.response.statusCode = HttpStatus.UNAUTHORIZED
            exchange.response.headers.contentType = MediaType.APPLICATION_JSON
            val errorResponse = ApiResponse.error<Any>(
                "Authentication failed: ${ex.message ?: "Invalid or expired token"}"
            )
            logger.warn("[AUTHENTICATION_FAILED] Method=${exchange.request.method} Path=${exchange.request.path} Message=${ex.message}")

            val bytes = objectMapper.writeValueAsBytes(errorResponse)
            val buffer: DataBuffer = exchange.response.bufferFactory().wrap(bytes)

            return exchange.response.writeWith(Mono.just(buffer))
        } finally {
            MDC.remove(MdcHelper.CORRELATION_ID_KEY)
        }
    }


}