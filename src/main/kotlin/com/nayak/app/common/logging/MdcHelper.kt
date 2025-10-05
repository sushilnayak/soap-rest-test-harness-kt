package com.nayak.app.common.logging

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.ReactorContext
import org.slf4j.MDC
object MdcHelper {
    const val CORRELATION_ID_KEY = "correlationId"

    /**
     * Get correlation ID from coroutine context
     */
    suspend fun getCorrelationId(): String? {
        return currentCoroutineContext()[ReactorContext]?.context?.get<String>(CORRELATION_ID_KEY)
    }

    /**
     * Execute a block with MDC context set from coroutine context
     */
    suspend fun <T> withMdc(block: suspend () -> T): T {
        val correlationId = getCorrelationId()
        return if (correlationId != null) {
            MDC.put(CORRELATION_ID_KEY, correlationId)
            try {
                block()
            } finally {
                MDC.remove(CORRELATION_ID_KEY)
            }
        } else {
            block()
        }
    }
}


suspend fun <T> withMdcContext(block: suspend () -> T): T {
    return MdcHelper.withMdc(block)
}