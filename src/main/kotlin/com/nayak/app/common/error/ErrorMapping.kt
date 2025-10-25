package com.nayak.app.common.error


import com.nayak.app.common.errors.DomainError
import kotlin.coroutines.cancellation.CancellationException

/** Map any Throwable to Database, but never swallow coroutine cancellations. */
fun Throwable.toDatabaseError(message: String = "Database operation failed"): DomainError.Database =
    when (this) {
        is CancellationException -> throw this
        else -> DomainError.Database(message)
    }

/** Marker to signal that a DB error should be surfaced as 409 Conflict. */
class ConflictWrappingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
