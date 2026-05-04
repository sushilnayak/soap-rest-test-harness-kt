package com.nayak.app.common.http

import arrow.core.Either
import com.nayak.app.common.errors.DomainError
import com.nayak.app.common.errors.toHttpStatus
import org.springframework.http.ResponseEntity

/**
 * Extension function to convert Either<DomainError, T> to ResponseEntity<ApiResponse<T>>
 * This provides a consistent way to handle Arrow Either results in controllers.
 */
fun <T> Either<DomainError, T>.toResponse(): ResponseEntity<ApiResponse<T>> =
    fold(
        ifLeft = { error ->
            ResponseEntity.status(error.toHttpStatus())
                .body(ApiResponse.error(error.message))
        },
        ifRight = { data ->
            ResponseEntity.ok(ApiResponse.success(data))
        }
    )

/**
 * Extension function with custom success message
 */
fun <T> Either<DomainError, T>.toResponse(successMessage: String): ResponseEntity<ApiResponse<T>> =
    fold(
        ifLeft = { error ->
            ResponseEntity.status(error.toHttpStatus())
                .body(ApiResponse.error(error.message))
        },
        ifRight = { data ->
            ResponseEntity.ok(ApiResponse.success(data, successMessage))
        }
    )
