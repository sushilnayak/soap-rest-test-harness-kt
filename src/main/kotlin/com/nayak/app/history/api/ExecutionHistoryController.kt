package com.nayak.app.history.api

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import com.nayak.app.bulk.repo.ResultRowProjection
import com.nayak.app.common.errors.DomainError
import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.history.app.ExecutionDownloadService
import com.nayak.app.history.app.ExecutionHistoryService
import com.nayak.app.history.domain.ExecutionHistoryItemDto
import com.nayak.app.history.domain.HistorySearchType
import com.nayak.app.project.app.PagedResult
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*


@RestController
@RequestMapping("/api/history")
class ExecutionHistoryController(
    private val historyService: ExecutionHistoryService,
    private val downloadService: ExecutionDownloadService,
) {

    @GetMapping
    suspend fun listHistory(
        @Parameter(description = "Page number (0-based)")
        @RequestParam(defaultValue = "0") @Min(0) page: Int,

        @Parameter(description = "Filter by search string")
        @RequestParam(required = false) search: HistorySearchType? = HistorySearchType.SELF,

        @Parameter(description = "Page size (1-100)")
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<PagedResult<ExecutionHistoryItemDto>>> =
        either {
            historyService.listHistoryPaginated(search = search!!, page = page, size = size, userId = userId).bind()
        }.toResponse()


    @GetMapping("/{executionId}")
    suspend fun getDetails(
        @PathVariable executionId: UUID
    ): ResponseEntity<ApiResponse<List<ResultRowProjection>>> =
        either {
            historyService.getDetails(executionId).bind()
        }.toResponse()

    @DeleteMapping("/{executionId}")
    suspend fun deleteExecutionHistory(
        @PathVariable executionId: UUID
    ): ResponseEntity<ApiResponse<String>> =
        either {
            historyService.deleteExecutionHistory(executionId).bind()
        }.toResponse()


    @GetMapping("/{executionId}/row/{rowIndex}/request")
    suspend fun downloadRowRequest(
        @PathVariable executionId: UUID,
        @PathVariable rowIndex: Int
    ): ResponseEntity<ByteArrayResource> =
        historyService.getRowRequestBody(executionId, rowIndex).fold(
            ifLeft = { e ->
                ResponseEntity.status(e.toHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ByteArrayResource("""{"error":"${e.message}"}""".toByteArray(StandardCharsets.UTF_8)))
            },
            ifRight = { json ->
                val filename = "execution_${executionId}_row_${rowIndex}_request.json"
                val headers = HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
                }
                ResponseEntity.ok()
                    .headers(headers)
                    .body(ByteArrayResource(json.toByteArray(StandardCharsets.UTF_8)))
            }
        )


    @GetMapping("/{executionId}/row/{rowIndex}/response")
    suspend fun downloadRowResponse(
        @PathVariable executionId: UUID,
        @PathVariable rowIndex: Int
    ): ResponseEntity<ByteArrayResource> =
        historyService.getRowResponseBody(executionId, rowIndex).fold(
            ifLeft = { e ->
                ResponseEntity.status(e.toHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ByteArrayResource("""{"error":"${e.message}"}""".toByteArray(StandardCharsets.UTF_8)))
            },
            ifRight = { json ->
                val filename = "execution_${executionId}_row_${rowIndex}_response.json"
                val headers = HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_JSON
                    add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
                }
                ResponseEntity.ok()
                    .headers(headers)
                    .body(ByteArrayResource(json.toByteArray(StandardCharsets.UTF_8)))
            }

        )

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

        val flux = dataBufferFlux(response.bufferFactory()) { os ->
            runBlocking {
                downloadService.writeAllRequestsZip(executionId, os).getOrElse { throw RuntimeException(it.message) }
            }
        }
        response.writeWith(flux).then()
    }.flatMap { it }

    @GetMapping("/{executionId}/download/all-responses", produces = ["application/zip"])
    fun downloadAllResponses(
        @PathVariable executionId: UUID,
        response: ServerHttpResponse
    ): Mono<Void> = mono {
        val fileName = downloadService.buildResponsesZipFileName(executionId).getOrElse { err ->
            response.statusCode = err.toHttpStatus()
            response.headers.contentType = MediaType.APPLICATION_JSON
            val buf = response.bufferFactory().wrap("""{"error":"${err.message}"}""".toByteArray())
            return@mono response.writeWith(Mono.just(buf)).then()
        }

        response.headers.contentType = MediaType.parseMediaType("application/zip")
        response.headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")

        val flux = dataBufferFlux(response.bufferFactory()) { os ->
            runBlocking {
                downloadService.writeAllRequestsZip(executionId, os).getOrElse { throw RuntimeException(it.message) }
            }
        }
        response.writeWith(flux).then()
    }.flatMap { it }


//    @GetMapping("/{executionId}/download/all-responses", produces = ["application/zip"])
//    suspend fun downloadAllResponses(
//        @PathVariable executionId: UUID,
//        response: ServerHttpResponse
//    ) {
//        val fileName = downloadService.buildResponsesZipFileName(executionId).getOrElse { err ->
//            response.statusCode = err.toHttpStatus()
//            response.headers.contentType = MediaType.APPLICATION_JSON
//            val buf = response.bufferFactory().wrap("""{"error":"${err.message}"}""".toByteArray())
//            response.writeWith(Flux.just(buf)).awaitSingleOrNull()
//            return
//        }
//
//        response.headers.contentType = MediaType.parseMediaType("application/zip")
//        response.headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
//
//        val input = PipedInputStream()
//        val output = PipedOutputStream(input)
//
//        val writerJob: Job = CoroutineScope(Dispatchers.IO).launch {
//            try {
//                downloadService.writeAllResponsesZip(executionId, output).fold(
//                    ifLeft = { /* swallow or log; stream may have started */ },
//                    ifRight = { }
//                )
//            } finally {
//                output.flush()
//                output.close()
//            }
//        }
//
//        val flux = DataBufferUtils.readInputStream(
//            { input },
//            response.bufferFactory(),
//            16 * 1024
//        ).doFinally {
//            try {
//                input.close()
//            } catch (_: Exception) {
//            }
//        }
//
//        response.writeWith(flux).awaitSingleOrNull()
//        writerJob.join()
//    }

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

//    @GetMapping("/{executionId}/download/all-requests", produces = ["application/zip"])
//    suspend fun downloadAllRequests(
//        @PathVariable executionId: UUID
//    ): ResponseEntity<ByteArrayResource> =
//        historyService.downloadAllRequestsZip(executionId).fold(
//            ifLeft = { e ->
//                val body = ByteArrayResource("""{"error":"${e.message}"}""".toByteArray())
//                ResponseEntity.status(e.toHttpStatus())
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .body(body)
//            },
//            ifRight = { (fileName, bytes) ->
//                val headers = HttpHeaders().apply {
//                    contentType = MediaType.parseMediaType("application/zip")
//                    add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
//                }
//                ResponseEntity.ok()
//                    .headers(headers)
//                    .body(ByteArrayResource(bytes))
//            }
//        )
//
//    @GetMapping("/{executionId}/download/all-responses", produces = ["application/zip"])
//    suspend fun downloadAllResponses(
//        @PathVariable executionId: UUID
//    ): ResponseEntity<ByteArrayResource> =
//        historyService.downloadAllResponsesZip(executionId).fold(
//            ifLeft = { e ->
//                val body = ByteArrayResource("""{"error":"${e.message}"}""".toByteArray())
//                ResponseEntity.status(e.toHttpStatus())
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .body(body)
//            },
//            ifRight = { (fileName, bytes) ->
//                val headers = HttpHeaders().apply {
//                    contentType = MediaType.parseMediaType("application/zip")
//                    add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
//                }
//                ResponseEntity.ok()
//                    .headers(headers)
//                    .body(ByteArrayResource(bytes))
//            }
//        )

    @GetMapping("/{executionId}/download/original")
    suspend fun downloadOriginalExcel(
        @PathVariable executionId: UUID
    ): ResponseEntity<ByteArray> =
        historyService.exportBulkExcel(executionId, withResults = false).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).build()
            },
            ifRight = { excelBytes ->
                ResponseEntity.ok()
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; bulk_${executionId}_original.xlsx")
                    .body(excelBytes)
            }
        )


    @GetMapping("/{executionId}/download/results")
    suspend fun downloadExcelWithResults(
        @PathVariable executionId: UUID
    ): ResponseEntity<ByteArray> =
        historyService.exportBulkExcel(executionId, withResults = true).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).build()
            },
            ifRight = { excelBytes ->
                ResponseEntity.ok()
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; bulk_${executionId}_with_results.xlsx")
                    .body(excelBytes)
            }
        )


    private fun okExcel(bytes: ByteArray, filename: String): ResponseEntity<ByteArray> {
        val encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''$encoded")
            .body(bytes)
    }

    private fun <A> Either<DomainError, A>.toResponse(): ResponseEntity<ApiResponse<A>> =
        fold(
            ifLeft = { e ->
                ResponseEntity.status(e.toHttpStatus()).body(ApiResponse.error(e.message))
            },
            ifRight = { a ->
                ResponseEntity.ok(ApiResponse.success(a))
            }
        )
}
