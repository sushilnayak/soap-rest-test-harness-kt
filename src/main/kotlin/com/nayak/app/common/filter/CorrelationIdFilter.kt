package com.nayak.app.common.filter

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

@Component
@Order(-1000)
class CorrelationIdFilter : WebFilter {

    private val logger = LoggerFactory.getLogger(CorrelationIdFilter::class.java)

    companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-ID"
        const val CORRELATION_ID_MDC_KEY = "correlationId"
    }


    override fun filter(
        exchange: ServerWebExchange, chain: WebFilterChain
    ): Mono<Void?> {

        val correlationId = exchange.request.headers.getFirst(CORRELATION_ID_HEADER)
            ?: generateCorrelationId()

        exchange.response.headers.add(CORRELATION_ID_HEADER, correlationId)

        exchange.attributes[CORRELATION_ID_MDC_KEY] = correlationId

        return chain.filter(exchange)
            .contextWrite { context ->
                context.put(CORRELATION_ID_MDC_KEY, correlationId)
            }
            .doOnEach { signal ->
                if (signal.isOnNext || signal.isOnError || signal.isOnComplete) {
                    MDC.put(CORRELATION_ID_MDC_KEY, correlationId)
                }
            }
            .doFinally {
                MDC.remove(CORRELATION_ID_MDC_KEY)
            }
    }

    fun generateCorrelationId(): String {
        return UUID.randomUUID().toString()
    }
}