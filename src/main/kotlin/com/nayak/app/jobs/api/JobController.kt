package com.nayak.app.jobs.api

import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.jobs.app.JobExecutionService
import com.nayak.app.jobs.domain.JobType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Job Management", description = "Asynchronous job execution and monitoring")
@SecurityRequirement(name = "bearer-jwt")
class JobController(private val jobExecutionService: JobExecutionService) {

    @GetMapping("/{executionId}")
    @Operation(summary = "Get job status by execution ID")
    suspend fun getJobStatus(
        @PathVariable executionId: String,
//        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<Any>> {
        return jobExecutionService.getJobStatus(
            executionId
//            , userId
        ).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { job ->
                ResponseEntity.ok(ApiResponse.success(job))
            }
        )
    }

    @GetMapping
    @Operation(summary = "Get all jobs for authenticated user")
    suspend fun getJobs(
        @RequestParam(required = false) jobType: JobType?,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<Any>> {
        return jobExecutionService.getJobsByOwner(userId, jobType).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { jobs ->
                ResponseEntity.ok(ApiResponse.success(jobs))
            }
        )
    }

    @PostMapping("/{executionId}/cancel")
    @Operation(summary = "Cancel a running job")
    suspend fun cancelJob(
        @PathVariable executionId: String,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<Any>> {
        return jobExecutionService.cancelJob(executionId, userId).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = {
                ResponseEntity.ok(ApiResponse.success(Unit, "Job cancelled successfully"))
            }
        )
    }
}