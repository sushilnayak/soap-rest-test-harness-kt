package com.nayak.app.common.security


import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.logging.MdcHelper
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.security.Principal

@Component
class CustomAccessDeniedHandler(
    private val objectMapper: ObjectMapper
) : ServerAccessDeniedHandler {

    override fun handle(
        exchange: ServerWebExchange,
        denied: AccessDeniedException
    ): Mono<Void> {
        val logger = LoggerFactory.getLogger(CustomAccessDeniedHandler::class.java)
        val correlationId = exchange.attributes[MdcHelper.CORRELATION_ID_KEY] as? String
            ?: exchange.request.headers.getFirst("X-Correlation-ID")
            ?: "UNKNOWN"
        MDC.put(MdcHelper.CORRELATION_ID_KEY, correlationId)

        try {
            exchange.response.statusCode = HttpStatus.FORBIDDEN
            exchange.response.headers.contentType = MediaType.APPLICATION_JSON

            val errorResponse = ApiResponse.error<Any>(
                "Access denied: ${denied.message ?: "Insufficient permissions"}"
            )
            logger.warn("[AUTHENTICATION_FAILED] Method=${exchange.request.method} Path=${exchange.request.path} Message=${denied.message}")

            val bytes = objectMapper.writeValueAsBytes(errorResponse)
            val buffer: DataBuffer = exchange.response.bufferFactory().wrap(bytes)


            return exchange.response.writeWith(Mono.just(buffer))
        } finally {
            MDC.remove(MdcHelper.CORRELATION_ID_KEY)
        }
    }


}