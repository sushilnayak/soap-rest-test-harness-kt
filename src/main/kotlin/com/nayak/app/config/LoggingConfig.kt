package com.nayak.app.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.*

@Configuration
class LoggingConfig {

    @Bean
    fun requestLoggingFilter(objectMapper: ObjectMapper): WebFilter {
        return RequestLoggingFilter(objectMapper)
    }
}

class RequestLoggingFilter(private val objectMapper: ObjectMapper) : WebFilter {
    private val logger = LoggerFactory.getLogger(RequestLoggingFilter::class.java)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request
        val requestId = UUID.randomUUID().toString().take(8)

        // Add request ID to exchange attributes for downstream use
        exchange.attributes["requestId"] = requestId

        val logData = mapOf(
            "requestId" to requestId,
            "method" to request.method.name(),
            "uri" to request.uri.toString(),
            "remoteAddress" to request.remoteAddress?.address?.hostAddress,
            "userAgent" to request.headers.getFirst("User-Agent"),
            "timestamp" to System.currentTimeMillis()
        )

        logger.info("HTTP Request: {}", objectMapper.writeValueAsString(logData))

        val startTime = System.currentTimeMillis()

        return chain.filter(exchange).doFinally { signalType ->
            val duration = System.currentTimeMillis() - startTime
            val response = exchange.response

            val responseLogData = mapOf(
                "requestId" to requestId,
                "statusCode" to response.statusCode?.value(),
                "duration" to duration,
                "signalType" to signalType.name
            )

            logger.info("HTTP Response: {}", objectMapper.writeValueAsString(responseLogData))
        }
    }
}