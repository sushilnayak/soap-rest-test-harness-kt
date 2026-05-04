package com.nayak.app.jobs.api

import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.http.toResponse
import com.nayak.app.jobs.app.JobExecutionService
import com.nayak.app.jobs.domain.JobExecution
import com.nayak.app.jobs.domain.JobType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Job Management", description = "Asynchronous job execution and monitoring endpoints")
@SecurityRequirement(name = "bearer-jwt")
class JobController(private val jobExecutionService: JobExecutionService) {

    @GetMapping("/{executionId}")
    @Operation(
        summary = "Get job status by execution ID",
        description = "Retrieve the current status and details of a job execution by its unique execution ID"
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Job status retrieved successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = JobExecution::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "id": "550e8400-e29b-41d4-a716-446655440000",
                                "jobType": "BULK_EXECUTION",
                                "executionId": "exec_abc123def456",
                                "status": "RUNNING",
                                "ownerId": "user123",
                                "progressInfo": {
                                    "totalItems": 100,
                                    "processedItems": 45,
                                    "successfulItems": 42,
                                    "failedItems": 3
                                },
                                "createdAt": "2024-01-15T10:00:00",
                                "startedAt": "2024-01-15T10:00:05",
                                "completedAt": null
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
                description = "Job not found",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Job not found",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "500",
                description = "Internal server error"
            )
        ]
    )
    suspend fun getJobStatus(
        @Parameter(description = "Unique execution ID of the job", example = "exec_abc123def456", required = true)
        @PathVariable executionId: String,
    ): ResponseEntity<ApiResponse<JobExecution>> =
        jobExecutionService.getJobStatus(executionId).toResponse()

    @GetMapping
    @Operation(
        summary = "Get all jobs for authenticated user",
        description = "Retrieve all job executions owned by the authenticated user, optionally filtered by job type"
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Jobs retrieved successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": [
                                {
                                    "id": "550e8400-e29b-41d4-a716-446655440000",
                                    "jobType": "BULK_EXECUTION",
                                    "executionId": "exec_abc123def456",
                                    "status": "COMPLETED",
                                    "ownerId": "user123",
                                    "createdAt": "2024-01-15T10:00:00",
                                    "completedAt": "2024-01-15T10:15:00"
                                }
                            ],
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
                responseCode = "500",
                description = "Internal server error"
            )
        ]
    )
    suspend fun getJobs(
        @Parameter(description = "Filter by job type (optional)", example = "BULK_EXECUTION")
        @RequestParam(required = false) jobType: JobType?,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<List<JobExecution>>> =
        jobExecutionService.getJobsByOwner(userId, jobType).toResponse()

    @PostMapping("/{executionId}/cancel")
    @Operation(
        summary = "Cancel a running job",
        description = "Cancel a job that is currently running or pending. Only the job owner can cancel their jobs."
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Job cancelled successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": null,
                            "message": "Job cancelled successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "400",
                description = "Bad request - job cannot be cancelled in current status",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Job cannot be cancelled in current status: COMPLETED",
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
                responseCode = "403",
                description = "Forbidden - user does not own this job",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Access denied",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "404",
                description = "Job not found"
            )
        ]
    )
    suspend fun cancelJob(
        @Parameter(
            description = "Unique execution ID of the job to cancel",
            example = "exec_abc123def456",
            required = true
        )
        @PathVariable executionId: String,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<Unit>> =
        jobExecutionService.cancelJob(executionId, userId).toResponse("Job cancelled successfully")
}
