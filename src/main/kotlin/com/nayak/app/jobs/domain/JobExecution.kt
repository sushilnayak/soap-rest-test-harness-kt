package com.nayak.app.jobs.domain

import com.fasterxml.jackson.databind.JsonNode
import com.nayak.app.bulk.domain.BulkExecutionRequest
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime
import java.util.*

@Table("job_executions")
data class JobExecution(
    @Id
    val id: UUID? = null,

    @Column("job_type")
    val jobType: JobType,

    @Column("execution_id")
    val executionId: String, // Unique trace ID for logging

    val status: JobStatus,

    @Column("owner_id")
    val ownerId: String,

    @Column("job_payload")
    val jobPayload: JsonNode, // Serialized job parameters

    @Column("retry_count")
    val retryCount: Int = 0,

    @Column("max_retries")
    val maxRetries: Int = 3,

    @Column("next_retry_at")
    val nextRetryAt: LocalDateTime? = null,

    @Column("started_at")
    val startedAt: LocalDateTime? = null,

    @Column("completed_at")
    val completedAt: LocalDateTime? = null,

    @Column("error_message")
    val errorMessage: String? = null,

    @Column("error_details")
    val errorDetails: JsonNode? = null,

    @Column("progress_info")
    val progressInfo: JsonNode? = null,

    @Column("created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column("updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class JobType {
    BULK_EXECUTION,
    TEST_GENERATION,
    TEMPLATE_PROCESSING
}

enum class JobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    RETRY_SCHEDULED
}

data class BulkExecutionJobPayload(
    val bulkExecutionId: UUID,
    val projectId: UUID,
    val request: BulkExecutionRequest,
    val excelData: ExcelJobData,
)

data class BulkExecutionResponseDto(
    val bulkExecutionId: UUID,
    val projectId: UUID
)

data class ExcelJobData(
    val headers: List<String>,
    val validRows: List<ExcelRowJobData>,
    val skippedRows: List<Int>
)

data class ExcelRowJobData(
    val originalRowIndex: Int,
    val data: Map<String, CellJobData>
)

data class CellJobData(
    val value: String,
    val isExcluded: Boolean = false
)

data class JobProgressInfo(
    val totalItems: Int,
    val processedItems: Int,
    val successfulItems: Int,
    val failedItems: Int,
    val currentItem: String? = null,
    val estimatedTimeRemaining: Long? = null
)