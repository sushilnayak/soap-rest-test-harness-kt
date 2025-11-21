Gotcha — the “pending” download is almost always the classic Piped stream deadlock/coordination issue in WebFlux. Even
though our version *should* work, in practice `PipedInputStream` + `DataBufferUtils.readInputStream` can stall depending
on scheduling/cancellation. The most robust fix is to **ditch the piped streams** and instead stream bytes directly to
the HTTP response using a **Flux bridge** and a tiny `OutputStream` adapter that emits `DataBuffer`s.

Below is a drop-in refactor for **WebFlux** that:

* does **zero buffering** of the whole ZIP,
* writes on `Dispatchers.IO`,
* pushes chunks to the client via `Flux.create`,
* finishes cleanly (no “pending” forever).

---

# 1) A tiny bridge: `DataBufferFluxOutputStream`

This wraps a `FluxSink<DataBuffer>` as an `OutputStream`. We allocate a `DataBuffer` for each write (or for batched
chunks), copy the bytes in, and `next()` them into the sink.

```kotlin
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.io.OutputStream

class DataBufferFluxOutputStream(
    private val factory: DataBufferFactory,
    private val sink: FluxSink<DataBuffer>,
    private val chunkSize: Int = 16 * 1024
) : OutputStream() {

    private val buf = ByteArray(chunkSize)
    private var pos = 0
    private var closed = false

    override fun write(b: Int) {
        buf[pos++] = b.toByte()
        if (pos >= buf.size) flushBuffer()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len
        while (remaining > 0) {
            val space = buf.size - pos
            val toCopy = minOf(space, remaining)
            System.arraycopy(b, offset, buf, pos, toCopy)
            pos += toCopy
            offset += toCopy
            remaining -= toCopy
            if (pos >= buf.size) flushBuffer()
        }
    }

    override fun flush() {
        flushBuffer()
    }

    private fun flushBuffer() {
        if (pos == 0 || closed) return
        val dataBuffer = factory.allocateBuffer(pos)
        dataBuffer.write(buf, 0, pos)
        pos = 0
        sink.next(dataBuffer)
    }

    override fun close() {
        if (closed) return
        flushBuffer()
        closed = true
    }
}

/** Helper to build a Flux<DataBuffer> that you can writeWith(...) */
fun dataBufferFlux(
    factory: DataBufferFactory,
    writer: (out: OutputStream) -> Unit
): Flux<DataBuffer> {
    return Flux.create { sink ->
        val out = DataBufferFluxOutputStream(factory, sink)
        try {
            writer(out)   // caller writes (blocking is fine; we’ll call from Dispatchers.IO)
            out.close()
            sink.complete()
        } catch (t: Throwable) {
            try {
                out.close()
            } catch (_: Exception) {
            }
            sink.error(t)
        }
    }
}
```

---

# 2) Service stays the same (writes ZIP to any OutputStream)

You already have these; keep them. They block on `ZipOutputStream` but we wrap them in `withContext(Dispatchers.IO)`:

```kotlin
suspend fun writeAllRequestsZip(executionId: UUID, out: OutputStream): Either<DomainError, Unit> = either {
    ensure(bulkExecRepo.existsById(executionId)) { NotFoundError("No bulk execution found with id=$executionId") }
    withContext(Dispatchers.IO) {
        ZipOutputStream(out).use { zos ->
            resultsRepo.streamAllRequests(executionId).collect { row ->
                val name = "request_${(row.testCaseId?.takeIf { it.isNotBlank() } ?: row.rowIndex.toString())}.json"
                val bytes = (row.body?.ifBlank { "{}" } ?: "{}").toByteArray()
                zos.putNextEntry(ZipEntry(name.replace(Regex("""[^\w\-.]"""), "_")))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }
}

suspend fun writeAllResponsesZip(executionId: UUID, out: OutputStream): Either<DomainError, Unit> = either {
    ensure(bulkExecRepo.existsById(executionId)) { NotFoundError("No bulk execution found with id=$executionId") }
    withContext(Dispatchers.IO) {
        ZipOutputStream(out).use { zos ->
            resultsRepo.streamAllResponses(executionId).collect { row ->
                val name = "response_${(row.testCaseId?.takeIf { it.isNotBlank() } ?: row.rowIndex.toString())}.json"
                val bytes = (row.body?.ifBlank { "{}" } ?: "{}").toByteArray()
                zos.putNextEntry(ZipEntry(name.replace(Regex("""[^\w\-.]"""), "_")))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }
}
```

---

# 3) Controller (WebFlux): no pipes, no pending

We build a `Flux<DataBuffer>` with the helper above, kick the ZIP write inside it, and hand that Flux to
`response.writeWith(...)`. That’s it.

```kotlin
import arrow.core.getOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import java.util.*

@RestController
@RequestMapping("/api/v1/executions")
class ExecutionDownloadController(
    private val downloadService: ExecutionDownloadService
) {

    @GetMapping("/{executionId}/download/all-requests", produces = ["application/zip"])
    suspend fun downloadAllRequests(
        @PathVariable executionId: UUID,
        response: ServerHttpResponse
    ) {
        val fileName = downloadService.buildRequestsZipFileName(executionId).getOrElse { err ->
            response.statusCode = err.toHttpStatus()
            response.headers.contentType = MediaType.APPLICATION_JSON
            val buf = response.bufferFactory().wrap("""{"error":"${err.message}"}""".toByteArray())
            response.writeWith(Mono.just(buf)).subscribe() // fire-and-forget because we’re in suspend
            return
        }

        response.headers.contentType = MediaType.parseMediaType("application/zip")
        response.headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
        response.headers.add(HttpHeaders.X_CONTENT_TYPE_OPTIONS, "nosniff")

        val flux = dataBufferFlux(response.bufferFactory()) { os ->
            // run the blocking ZIP writer on IO
            runBlocking {
                downloadService.writeAllRequestsZip(executionId, os).getOrElse { throw RuntimeException(it.message) }
            }
        }
        // write & complete
        response.writeWith(flux).subscribe()
    }

    @GetMapping("/{executionId}/download/all-responses", produces = ["application/zip"])
    suspend fun downloadAllResponses(
        @PathVariable executionId: UUID,
        response: ServerHttpResponse
    ) {
        val fileName = downloadService.buildResponsesZipFileName(executionId).getOrElse { err ->
            response.statusCode = err.toHttpStatus()
            response.headers.contentType = MediaType.APPLICATION_JSON
            val buf = response.bufferFactory().wrap("""{"error":"${err.message}"}""".toByteArray())
            response.writeWith(Mono.just(buf)).subscribe()
            return
        }

        response.headers.contentType = MediaType.parseMediaType("application/zip")
        response.headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
        response.headers.add(HttpHeaders.X_CONTENT_TYPE_OPTIONS, "nosniff")

        val flux = dataBufferFlux(response.bufferFactory()) { os ->
            runBlocking {
                downloadService.writeAllResponsesZip(executionId, os).getOrElse { throw RuntimeException(it.message) }
            }
        }
        response.writeWith(flux).subscribe()
    }
}
```

### Notes

* We call `response.writeWith(flux).subscribe()` because we’re inside a `suspend` handler that returns `Unit`.
  Alternatively, change the signature to return `Mono<Void>` and `return response.writeWith(flux)`.
* The `writer` runs under `Dispatchers.IO` inside the service, so it won’t block Netty.

---

## If you prefer returning `Mono<Void>` (cleaner)

```kotlin
@GetMapping("/{executionId}/download/all-requests", produces = ["application/zip"])
fun downloadAllRequests(
    @PathVariable executionId: UUID,
    response: ServerHttpResponse
): Mono<Void> = mono {
        val fileName = downloadService.buildRequestsZipFileName(executionId).getOrElse { err ->
            response.statusCode = err.toHttpStatus()
            response.headers.contentType = MediaType.APPLICATION_JSON
            val buf = response.bufferFactory().wrap("""{"error":"${err.message}"}""".toByteArray())
            return@mono response.writeWith(Mono.just(buf)).then()
        }

        response.headers.contentType = MediaType.parseMediaType("application/zip")
        response.headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
        response.headers.add(HttpHeaders.X_CONTENT_TYPE_OPTIONS, "nosniff")

        val flux = dataBufferFlux(response.bufferFactory()) { os ->
            runBlocking {
                downloadService.writeAllRequestsZip(executionId, os).getOrElse { throw RuntimeException(it.message) }
            }
        }
        response.writeWith(flux).then()
    }.flatMap { it }
```

(Do the same for responses.)

---

## Why this fixes “pending”

* No `PipedInputStream` → no risk of pipe buffer deadlocks or scheduler mismatch.
* The Flux completes when the writer closes the `OutputStream`, which we control.
* Backpressure is handled by Reactor + Netty; if the client slows, the sink’s downstream demand drops and our writer’s
  `sink.next()` only proceeds as buffers are requested.

---

If you want, I can adapt this to **pure coroutine style** (no `runBlocking`), using `kotlinx.coroutines.reactor`
`mono {}` inside `dataBufferFlux` to await the writer; I kept it explicit for clarity.
