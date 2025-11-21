package com.nayak.app.bulk.model


import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.*

@Table("bulk_execution_results")
data class BulkExecutionResultsEntity(

    @Id
    @Column("row_index")
    val rowIndex: Int,

    @Column("bulk_execution_id")
    val bulkExecutionId: UUID,

    @Column("test_case_id")
    val testCaseId: String? = null,

    @Column("description")
    val description: String? = null,

    @Column("request_body")
    val requestBody: String? = null, // stored as JSONB in DB

    @Column("response_body")
    val responseBody: String? = null, // stored as JSONB in DB

    @Column("status_code")
    val statusCode: Int? = null,

    @Column("success")
    val success: Boolean? = null,

    @Column("error")
    val error: String? = null,

    @Column("execution_time_ms")
    val executionTimeMs: Int? = null,

    @Column("created_at")
    val createdAt: OffsetDateTime? = null
)
