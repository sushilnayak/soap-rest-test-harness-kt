package com.nayak.app.history.api

import arrow.core.Either
import arrow.core.raise.either
import com.nayak.app.bulk.repo.BulkExecutionRowProjection
import com.nayak.app.common.errors.DomainError
import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.history.app.ExecutionHistoryService
import com.nayak.app.history.domain.ExecutionHistoryItemDto
import com.nayak.app.history.domain.HistorySearchType
import com.nayak.app.project.app.PagedResult
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.*


@RestController
@RequestMapping("/api/history")
class ExecutionHistoryController(
    private val historyService: ExecutionHistoryService
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
    ): ResponseEntity<ApiResponse<List<BulkExecutionRowProjection>>> =
        either {
            historyService.getDetails(executionId).bind()
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
    suspend fun downloadAllRequests(
        @PathVariable executionId: UUID
    ): ResponseEntity<ByteArrayResource> =
        historyService.downloadAllRequestsZip(executionId).fold(
            ifLeft = { e ->
                val body = ByteArrayResource("""{"error":"${e.message}"}""".toByteArray())
                ResponseEntity.status(e.toHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
            },
            ifRight = { (fileName, bytes) ->
                val headers = HttpHeaders().apply {
                    contentType = MediaType.parseMediaType("application/zip")
                    add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
                }
                ResponseEntity.ok()
                    .headers(headers)
                    .body(ByteArrayResource(bytes))
            }
        )

    @GetMapping("/{executionId}/download/all-responses", produces = ["application/zip"])
    suspend fun downloadAllResponses(
        @PathVariable executionId: UUID
    ): ResponseEntity<ByteArrayResource> =
        historyService.downloadAllResponsesZip(executionId).fold(
            ifLeft = { e ->
                val body = ByteArrayResource("""{"error":"${e.message}"}""".toByteArray())
                ResponseEntity.status(e.toHttpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
            },
            ifRight = { (fileName, bytes) ->
                val headers = HttpHeaders().apply {
                    contentType = MediaType.parseMediaType("application/zip")
                    add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
                }
                ResponseEntity.ok()
                    .headers(headers)
                    .body(ByteArrayResource(bytes))
            }
        )

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
