package com.nayak.app.bulk.api

import com.nayak.app.bulk.app.BulkExecutionService
import com.nayak.app.bulk.domain.BulkExecutionRequest
import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/bulk")
@Tag(name = "Bulk Execution", description = "Bulk execution using Excel templates")
class BulkExecutionController(private val bulkExecutionService: BulkExecutionService) {

    @PostMapping("/execute", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(summary = "Perform a single bulk execution")
    suspend fun executeBulk(
        @RequestPart("request") request: BulkExecutionRequest,
        @RequestPart("file") file: org.springframework.http.codec.multipart.FilePart,
        @AuthenticationPrincipal executorId: String
    ): ResponseEntity<ApiResponse<Any>> {

        val name = file.filename().lowercase()
        if (!(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Please provide an XLSX/XLS file"))
        }

        val tempFile = kotlin.io.path.createTempFile("bulk-", "-" + file.filename()).toFile()
        file.transferTo(tempFile).awaitSingleOrNull()
        return tempFile.inputStream().use { inputStream ->
            bulkExecutionService.processBulkExecution(request, inputStream, executorId).fold(
                ifLeft = { error ->
                    ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error(error.message))
                },
                ifRight = { execution -> ResponseEntity.ok(ApiResponse.success(execution, "Bulk execution started")) }
            )
        }
    }

    @GetMapping("/status/{id}")
    @Operation(summary = "Get bulk execution status")
    suspend fun getBulkExecution(@PathVariable id: UUID): ResponseEntity<ApiResponse<Any>> {
        return bulkExecutionService.getBulkExecutionStatus(id).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { execution ->
                ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(execution, "Bulk execution started"))
            }
        )
    }

// Webflux uses PartFile instead of MultiPartFile and that does not provide content type, we have to be satisfied with extension only :(
//    private fun isExcelFile(file: MultipartFile): Boolean {
//        val contentType = file.contentType
//        return contentType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
//                contentType == "application/vnd.ms-excel" ||
//                file.originalFilename?.endsWith(".xlsx") == true ||
//                file.originalFilename?.endsWith(".xls") == true
//    }
}