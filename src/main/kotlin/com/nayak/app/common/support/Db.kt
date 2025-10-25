package com.nayak.app.common.support

import arrow.core.Either
import com.nayak.app.common.error.toDatabaseError
import com.nayak.app.common.errors.DomainError
import kotlin.coroutines.cancellation.CancellationException

/** Guarded DB call wrapper. Use: db("message") { repo.doThing() } */
inline fun <A> db(msg: String, block: () -> A): Either<DomainError, A> =
    Either.catch(block).mapLeft { t ->
        t.printStackTrace()

        if (t is CancellationException) throw t
        t.toDatabaseError(msg)
    }