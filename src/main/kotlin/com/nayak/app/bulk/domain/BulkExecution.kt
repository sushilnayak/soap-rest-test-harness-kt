package com.nayak.app.bulk.domain

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime
import java.util.*

@Table("bulk_executions")
data class BulkExecution(
    @Id
    val id: UUID? = null,

    @Column("project_id")
    val projectId: UUID,

    @Column("executor_id")
    val executorId: String,

    val status: BulkExecutionStatus,

    @Column("total_rows")
    val totalRows: Int,

    @Column("processed_rows")
    val processedRows: Int = 0,

    @Column("successful_rows")
    val successfulRows: Int = 0,

    @Column("failed_rows")
    val failedRows: Int = 0,

    val results: JsonNode? = null,

    @Column("error_details")
    val errorDetails: String? = null,

    @Column("created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column("updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

enum class BulkExecutionStatus {
    PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
}

data class BulkExecutionRequest(
    val projectId: UUID,
    val executeImmediately: Boolean = true,
    val conversionMode: ConversionMode = ConversionMode.NONE,
    val targetUrl: String? = null // Override project URL if needed
)

enum class ConversionMode {
    NONE,           // Use project as-is
    SOAP_TO_REST,   // Convert SOAP project to REST calls
    REST_TO_SOAP    // Convert REST project to SOAP calls
}
data class BulkExecutionResult(
    val rowIndex: Int,
    val success: Boolean,
    val statusCode: Int? = null,
    val responseBody: String? = null,
    val error: String? = null,
    val executionTimeMs: Long = 0
)