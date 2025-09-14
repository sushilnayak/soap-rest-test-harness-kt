package com.nayak.app.common.errors

import org.springframework.http.HttpStatus

sealed class DomainError(val message: String) {
    data class Validation(val msg: String) : DomainError(msg)
    data class NotFound(val msg: String) : DomainError(msg)
    data class Forbidden(val msg: String) : DomainError(msg)
    data class Conflict(val msg: String) : DomainError(msg)
    data class Database(val msg: String) : DomainError(msg)
    data class Authentication(val msg: String) : DomainError(msg)
    data class Authorization(val msg: String) : DomainError(msg)
    data class External(val msg: String) : DomainError(msg)
}

fun DomainError.toHttpStatus(): HttpStatus {
    return when (this) {
        is DomainError.Validation -> HttpStatus.BAD_REQUEST
        is DomainError.NotFound -> HttpStatus.NOT_FOUND
        is DomainError.Forbidden -> HttpStatus.FORBIDDEN
        is DomainError.Conflict -> HttpStatus.CONFLICT
        is DomainError.Database -> HttpStatus.INTERNAL_SERVER_ERROR
        is DomainError.Authentication -> HttpStatus.UNAUTHORIZED
        is DomainError.Authorization -> HttpStatus.FORBIDDEN
        is DomainError.External -> HttpStatus.BAD_GATEWAY
    }
}