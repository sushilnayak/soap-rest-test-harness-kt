package com.nayak.app.history.domain

import com.nayak.app.bulk.domain.BulkExecutionStatus
import java.time.Instant
import java.time.LocalDateTime
import java.util.*

data class ExecutionHistoryItemDto(
    val id: UUID,
    val title: String,
    val project: String,
    val type: ExecutionType,         // API_EXECUTION | BULK_EXECUTION | TEST_GENERATION
    val status: BulkExecutionStatus,              // "success" | "error" | "processing" | "pending"
    val executedAt: LocalDateTime,
    val duration: String,            // e.g., "2m 13s"
    val details: BulkDetails? = null // only for BULK_EXECUTION (optional)
) {
    data class BulkDetails(
        val rowCount: Int,
        val successCount: Int,
        val failureCount: Int
    )
}

enum class HistorySearchType { SELF, ALL }

enum class ExecutionType { API_EXECUTION, BULK_EXECUTION, TEST_GENERATION }

data class ExecutionDetails(
    val id: UUID,
    val title: String,
    val project: String,
    val type: ExecutionType,
    val status: String,
    val executedAt: Instant,
    val duration: String,
    val summary: String?,
    val metadata: Map<String, Any?> = emptyMap(),
    val steps: List<Step> = emptyList()
) {
    data class Step(
        val name: String,
        val status: String,
        val startedAt: Instant?,
        val endedAt: Instant?,
        val message: String? = null
    )
}

/** Artifact description for download endpoint */
data class ExecutionArtifact(
    val filename: String,
    val mediaType: org.springframework.http.MediaType,
    val size: Long?,
    val stream: java.io.InputStream
)
