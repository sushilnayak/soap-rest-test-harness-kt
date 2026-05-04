package com.nayak.app.bulk.api

import com.nayak.app.bulk.app.BulkExecutionResponseDto
import com.nayak.app.bulk.app.BulkExecutionService
import com.nayak.app.bulk.domain.BulkExecution
import com.nayak.app.bulk.domain.BulkExecutionRequest
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.http.toResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.*
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@RestController
@RequestMapping("/api/bulk")
@Tag(name = "Bulk Execution", description = "Execute multiple test cases from Excel templates")
@SecurityRequirement(name = "bearer-jwt")
class BulkExecutionController(private val bulkExecutionService: BulkExecutionService) {

    @PostMapping("/execute", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Execute bulk test cases from Excel",
        description = """
            Upload an Excel file with test cases and execute them in bulk. The Excel file should contain:
            - Header row with column names
            - Data rows with test case parameters
            - Optional 'Skip Case(Y/N)' column to skip specific rows
            - Cell colors can be used to exclude specific values (if respectCellColors is true)
        """
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Bulk execution started successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = BulkExecutionResponseDto::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "bulkExecutionId": "550e8400-e29b-41d4-a716-446655440000",
                                "projectId": "660e8400-e29b-41d4-a716-446655440001"
                            },
                            "message": "Bulk execution started",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "400",
                description = "Bad request - invalid file format or validation failed",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Please provide an XLSX/XLS file",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "401",
                description = "Unauthorized - invalid or missing token"
            ),
            SwaggerApiResponse(
                responseCode = "404",
                description = "Project not found"
            ),
            SwaggerApiResponse(
                responseCode = "500",
                description = "Internal server error - Excel parsing or execution failed"
            )
        ]
    )
    suspend fun executeBulk(
        @Parameter(
            description = "Bulk execution configuration",
            required = true,
            schema = Schema(implementation = BulkExecutionRequest::class)
        )
        @RequestPart("request") request: BulkExecutionRequest,

        @Parameter(
            description = "Excel file (.xlsx or .xls) containing test cases",
            required = true,
            content = [Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")]
        )
        @RequestPart("file") file: org.springframework.http.codec.multipart.FilePart,

        @AuthenticationPrincipal executorId: String
    ): ResponseEntity<ApiResponse<BulkExecutionResponseDto>> {

        val name = file.filename().lowercase()
        if (!(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Please provide an XLSX/XLS file"))
        }

        val tempFile = kotlin.io.path.createTempFile("bulk-", "-" + file.filename()).toFile()
        file.transferTo(tempFile).awaitSingleOrNull()
        return tempFile.inputStream().use { inputStream ->
            bulkExecutionService.processBulkExecution(request, inputStream, executorId)
                .toResponse("Bulk execution started")
        }
    }

    @GetMapping("/status/{id}")
    @Operation(
        summary = "Get bulk execution status",
        description = "Retrieve the current status and progress of a bulk execution job"
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Bulk execution status retrieved successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = BulkExecution::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "id": "550e8400-e29b-41d4-a716-446655440000",
                                "projectId": "660e8400-e29b-41d4-a716-446655440001",
                                "ownerId": "user123",
                                "projectName": "User API Tests",
                                "status": "PROCESSING",
                                "totalRows": 100,
                                "processedRows": 45,
                                "successfulRows": 42,
                                "failedRows": 3,
                                "createdAt": "2024-01-15T10:00:00",
                                "updatedAt": "2024-01-15T10:15:00"
                            },
                            "message": null,
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "401",
                description = "Unauthorized - invalid or missing token"
            ),
            SwaggerApiResponse(
                responseCode = "404",
                description = "Bulk execution not found",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Bulk execution not found",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            )
        ]
    )
    suspend fun getBulkExecution(
        @Parameter(description = "Bulk execution ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
        @PathVariable id: UUID
    ): ResponseEntity<ApiResponse<BulkExecution>> =
        bulkExecutionService.getBulkExecutionStatus(id).toResponse()
}

//@Schema(description = "Bulk execution configuration")
//data class BulkExecutionRequestSchema(
//    @field:NotNull(message = "Project ID cannot be null")
//    @Schema(
//        description = "ID of the project to execute test cases against",
//        example = "550e8400-e29b-41d4-a716-446655440000",
//        required = true
//    )
//    val projectId: UUID,
//
//    @Schema(
//        description = "Whether to start execution immediately or queue it",
//        example = "true",
//        defaultValue = "true"
//    )
//    val executeImmediately: Boolean = true,
//
//    @Schema(
//        description = "Conversion mode for request/response transformation",
//        example = "NONE",
//        defaultValue = "NONE",
//        allowableValues = ["NONE", "SOAP_TO_REST", "REST_TO_SOAP"]
//    )
//    val conversionMode: ConversionMode = ConversionMode.NONE,
//
//    @Schema(
//        description = "Cache authentication token for the entire bulk execution",
//        example = "true",
//        defaultValue = "true"
//    )
//    val cacheAuthToken: Boolean = true,
//
//    @Schema(
//        description = "Use cell background colors to exclude values from execution",
//        example = "true",
//        defaultValue = "true"
//    )
//    val respectCellColors: Boolean = true,
//
//    @Schema(
//        description = "Append execution results to the Excel file with ACTUAL_ prefix",
//        example = "true",
//        defaultValue = "true"
//    )
//    val appendResults: Boolean = true
//)
