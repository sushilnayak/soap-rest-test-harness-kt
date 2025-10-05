package com.nayak.app.common.api

import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.logging.MdcHelper
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.FieldError
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.ServerWebInputException
import java.security.Principal
import java.util.*

/**
 * Extract correlation ID from exchange
 */
public fun getCorrelationId(exchange: ServerWebExchange): String {
    return exchange.attributes[MdcHelper.CORRELATION_ID_KEY] as? String
        ?: exchange.request.headers.getFirst("X-Correlation-ID")
        ?: "UNKNOWN"
}

/**
 * Extract current user from security context
 */
public suspend fun getCurrentUser(exchange: ServerWebExchange): String {
    return exchange.getPrincipal<Principal>()
        .map { principal -> principal.name ?: principal.toString() }
        .awaitSingleOrNull() ?: "anonymous"
}

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)



    /**
     * Handle validation errors from @Valid annotation
     */
    @ExceptionHandler(WebExchangeBindException::class)
    suspend fun handleValidationException(
        ex: WebExchangeBindException,
        exchange: ServerWebExchange
    ): ResponseEntity<ApiResponse<Nothing>> {
        val correlationId = getCorrelationId(exchange)
        MDC.put(MdcHelper.CORRELATION_ID_KEY, correlationId)

        try {
            val path = exchange.request.path.value()
            val method = exchange.request.method.name()
            val user = getCurrentUser(exchange)

            // Collect all field validation errors
            val fieldErrors = ex.bindingResult.allErrors.mapNotNull { error ->
                when (error) {
                    is FieldError -> "${error.field}: ${error.defaultMessage}"
                    else -> error.defaultMessage
                }
            }

            val errorMessage = if (fieldErrors.size == 1) {
                fieldErrors.first()
            } else {
                "Validation failed: ${fieldErrors.joinToString(", ")}"
            }

            logger.warn(
                "[VALIDATION_ERROR] Method=$method Path=$path Errors=${fieldErrors.joinToString("; ")} User=$user"
            )

            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(
                    ApiResponse.error<Nothing>(
                        errorMessage
                    )
                )
        } finally {
            MDC.remove(MdcHelper.CORRELATION_ID_KEY)
        }
    }

    /**
     * Handle invalid request input
     */
    @ExceptionHandler(ServerWebInputException::class)
    suspend fun handleServerWebInputException(
        ex: ServerWebInputException,
        exchange: ServerWebExchange
    ): ResponseEntity<ApiResponse<Nothing>> {
        val correlationId = getCorrelationId(exchange)
        MDC.put(MdcHelper.CORRELATION_ID_KEY, correlationId)

        try {
            val path = exchange.request.path.value()
            val method = exchange.request.method.name()
            val user = getCurrentUser(exchange)

            logger.warn(
                "[INPUT_ERROR] Method=$method Path=$path Reason=${ex.reason} Message=${ex.message} User=$user"
            )

            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(
                    ApiResponse.error<Nothing>(
                        "Invalid request: ${ex.reason ?: "Malformed input"}"
                    )
                )
        } finally {
            MDC.remove(MdcHelper.CORRELATION_ID_KEY)
        }
    }

    /**
     * Handle Spring Security AuthenticationException
     */
    @ExceptionHandler(AuthenticationException::class)
    suspend fun handleAuthenticationException(
        ex: AuthenticationException,
        exchange: ServerWebExchange
    ): ResponseEntity<ApiResponse<Nothing>> {
        val correlationId = getCorrelationId(exchange)
        MDC.put(MdcHelper.CORRELATION_ID_KEY, correlationId)

        try {
            val path = exchange.request.path.value()
            val method = exchange.request.method.name()
            val user = getCurrentUser(exchange)

            logger.warn(
                "[AUTHENTICATION_ERROR] Method=$method Path=$path Message=${ex.message} User=$user"
            )

            return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(
                    ApiResponse.error<Nothing>(
                        "Authentication failed: ${ex.message}"
                    )
                )
        } finally {
            MDC.remove(MdcHelper.CORRELATION_ID_KEY)
        }
    }

    /**
     * Handle Spring Security AccessDeniedException
     */
    @ExceptionHandler(AccessDeniedException::class)
    suspend fun handleAccessDeniedException(
        ex: AccessDeniedException,
        exchange: ServerWebExchange
    ): ResponseEntity<ApiResponse<Nothing>> {
        val correlationId = getCorrelationId(exchange)
        MDC.put(MdcHelper.CORRELATION_ID_KEY, correlationId)

        try {
            val path = exchange.request.path.value()
            val method = exchange.request.method.name()
            val user = getCurrentUser(exchange)

            logger.warn(
                "[ACCESS_DENIED] Method=$method Path=$path Message=${ex.message} User=$user"
            )

            return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(
                    ApiResponse.error<Nothing>(
                        "Access denied: Insufficient permissions"
                    )
                )
        } finally {
            MDC.remove(MdcHelper.CORRELATION_ID_KEY)
        }
    }

    /**
     * Handle IllegalArgumentException
     */
    @ExceptionHandler(IllegalArgumentException::class)
    suspend fun handleIllegalArgumentException(
        ex: IllegalArgumentException,
        exchange: ServerWebExchange
    ): ResponseEntity<ApiResponse<Nothing>> {
        val correlationId = getCorrelationId(exchange)
        MDC.put(MdcHelper.CORRELATION_ID_KEY, correlationId)

        try {
            val path = exchange.request.path.value()
            val method = exchange.request.method.name()
            val user = getCurrentUser(exchange)

            logger.warn(
                "[ILLEGAL_ARGUMENT] Method=$method Path=$path Message=${ex.message} User=$user"
            )

            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(
                    ApiResponse.error<Nothing>(
                        ex.message ?: "Invalid argument provided"
                    )
                )
        } finally {
            MDC.remove(MdcHelper.CORRELATION_ID_KEY)
        }
    }

    /**
     * Handle IllegalStateException
     */
    @ExceptionHandler(IllegalStateException::class)
    suspend fun handleIllegalStateException(
        ex: IllegalStateException,
        exchange: ServerWebExchange
    ): ResponseEntity<ApiResponse<Nothing>> {
        val correlationId = getCorrelationId(exchange)
        MDC.put(MdcHelper.CORRELATION_ID_KEY, correlationId)

        try {
            val path = exchange.request.path.value()
            val method = exchange.request.method.name()
            val user = getCurrentUser(exchange)

            logger.error(
                "[ILLEGAL_STATE] Method=$method Path=$path Message=${ex.message} User=$user StackTrace=${ex.stackTraceToString()}"
            )

            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    ApiResponse.error<Nothing>(
                        "Internal server error occurred"
                    )
                )
        } finally {
            MDC.remove(MdcHelper.CORRELATION_ID_KEY)
        }
    }

    /**
     * Handle all other unexpected exceptions
     */
    @ExceptionHandler(Exception::class)
    suspend fun handleGenericException(
        ex: Exception,
        exchange: ServerWebExchange
    ): ResponseEntity<ApiResponse<Nothing>> {
        val correlationId = getCorrelationId(exchange)
        MDC.put(MdcHelper.CORRELATION_ID_KEY, correlationId)

        try {
            val path = exchange.request.path.value()
            val method = exchange.request.method.name()
            val user = getCurrentUser(exchange)

            // Log the full stack trace for unexpected errors
            logger.error(
                "[UNEXPECTED_ERROR] Method=$method Path=$path Exception=${ex.javaClass.simpleName} Message=${ex.message} User=$user StackTrace=${ex.stackTraceToString()}"
            )

            // Don't expose internal error details to clients
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                    ApiResponse.error<Nothing>(
                        "An unexpected error occurred. Please contact support with correlation ID: $correlationId"
                    )
                )
        } finally {
            MDC.remove(MdcHelper.CORRELATION_ID_KEY)
        }
    }
}