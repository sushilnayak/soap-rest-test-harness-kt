package com.nayak.app.bulk.repo

import com.nayak.app.bulk.domain.BulkExecution
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.OffsetDateTime
import java.util.*

interface BulkExecutionRepository : CoroutineCrudRepository<BulkExecution, UUID> {


    @Query(
        """
        SELECT COUNT(*) FROM th_kt_bulk_executions  
          WHERE 1=1
          AND  (:racfId IS NULL OR owner_id  =  :racfId)
    """
    )
    suspend fun countBySearchType(racfId: String?): Long


    @Query(
        """
        SELECT * FROM th_kt_bulk_executions
          WHERE 1=1
          AND (:racfId IS NULL OR owner_id = :racfId)
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
    """
    )
    fun findAllPaginated(racfId: String?, limit: Int, offset: Long): Flow<BulkExecution>


    @Query("DELETE FROM th_kt_bulk_executions WHERE id = :id")
    suspend fun deleteByIdAndReturnCount(id: UUID): Int

//    @Query(
//        """
//        SELECT
//          res->>'requestBody'                       AS request_body,
//          res->>'responseBody'                      AS response_body,
//          (res->>'executionTimeMs')::int            AS execution_time_ms,
//          (res->>'statusCode')::int                 AS status_code,
//          (res->>'rowIndex')::int                   AS row_index,
//          res->>'error'                             AS error,
//          res->>'success'                           AS success,
//          vr.vrow->'data'->'Test Case ID'->>'value' AS test_case_id,
//          vr.vrow->'data'->'Description'->>'value'  AS description
//        FROM th_kt_bulk_executions t
//        JOIN job_executions j
//          ON j.execution_id = t.id::text
//        CROSS JOIN LATERAL jsonb_array_elements(t.results) AS res
//        CROSS JOIN LATERAL (
//          SELECT v.vrow
//          FROM jsonb_array_elements(j.job_payload->'excelData'->'validRows') AS v(vrow)
//          WHERE (v.vrow->>'originalRowIndex')::int = (res->>'rowIndex')::int
//          LIMIT 1
//        ) AS vr
//        WHERE t.id = :bulkId
//        ORDER BY (res->>'rowIndex')::int
//        """
//    )
//    fun findRowsByBulkId(bulkId: UUID): Flow<BulkExecutionRowProjection>

    @Query(
        """
        SELECT
          (res ? 'requestBody' AND res->>'requestBody' IS NOT NULL AND res->>'requestBody' <> '') AS has_request_body,
          (res ? 'responseBody' AND res->>'responseBody' IS NOT NULL AND res->>'responseBody' <> '') AS has_response_body,
          (res->>'executionTimeMs')::int            AS execution_time_ms,
          (res->>'statusCode')::int                 AS status_code,
          (res->>'rowIndex')::int                   AS row_index,
          res->>'error'                             AS error,
          (res->>'success')::boolean                AS success,
          vr.vrow->'data'->'Test Case ID'->>'value' AS test_case_id,
          vr.vrow->'data'->'Description'->>'value'  AS description
        FROM th_kt_bulk_executions t
        JOIN job_executions j
          ON j.execution_id = t.id::text
        CROSS JOIN LATERAL jsonb_array_elements(t.results) AS res
        CROSS JOIN LATERAL (
          SELECT v.vrow
          FROM jsonb_array_elements(j.job_payload->'excelData'->'validRows') AS v(vrow)
          WHERE (v.vrow->>'originalRowIndex')::int = (res->>'rowIndex')::int
          LIMIT 1
        ) AS vr
        WHERE t.id = :bulkId
        ORDER BY (res->>'rowIndex')::int
        """
    )
    fun findRowsByBulkId(bulkId: UUID): Flow<BulkExecutionRowProjection>

    @Query(
        """
        SELECT (res->>'requestBody')::jsonb AS body
        FROM th_kt_bulk_executions t
        CROSS JOIN LATERAL jsonb_array_elements(t.results) AS res
        WHERE t.id = :bulkId
          AND (res->>'rowIndex')::int = :rowIndex
        LIMIT 1
        """
    )
    suspend fun findRequestBodyByBulkIdAndRowIndex(bulkId: UUID, rowIndex: Int): String?

    @Query(
        """
        SELECT (res->>'responseBody')::jsonb AS body
        FROM th_kt_bulk_executions t
        CROSS JOIN LATERAL jsonb_array_elements(t.results) AS res
        WHERE t.id = :bulkId
          AND (res->>'rowIndex')::int = :rowIndex
        LIMIT 1
        """
    )
    suspend fun findResponseBodyByBulkIdAndRowIndex(bulkId: UUID, rowIndex: Int): String?


    @Query(
        """
        SELECT created_at
        FROM th_kt_bulk_executions
        WHERE id = :bulkId
        """
    )
    suspend fun findCreatedAt(bulkId: UUID): OffsetDateTime?

    @Query(
        """
        SELECT
          COALESCE(vr.vrow->'data'->'Test Case ID'->>'value', (res->>'rowIndex')) AS test_case_id,
          (res->>'rowIndex')::int                                                AS row_index,
          res->>'requestBody'                                                    AS body
        FROM th_kt_bulk_executions t
        JOIN job_executions j
          ON j.execution_id = t.id::text
          -- If you actually link via payload, use this instead:
          -- ON j.job_payload->>'bulkExecutionId' = t.id::text
        CROSS JOIN LATERAL jsonb_array_elements(t.results) AS res
        LEFT  JOIN LATERAL (
          SELECT v.vrow
          FROM jsonb_array_elements(j.job_payload->'excelData'->'validRows') AS v(vrow)
          WHERE (v.vrow->>'originalRowIndex')::int = (res->>'rowIndex')::int
          LIMIT 1
        ) AS vr ON TRUE
        WHERE t.id = :bulkId
        ORDER BY row_index
        """
    )
    fun streamAllRequests(bulkId: UUID): Flow<RowBodyProjection>


    @Query(
        """
        SELECT
          COALESCE(vr.vrow->'data'->'Test Case ID'->>'value', (res->>'rowIndex')) AS test_case_id,
          (res->>'rowIndex')::int                                                AS row_index,
          res->>'responseBody'                                                   AS body
        FROM th_kt_bulk_executions t
        JOIN job_executions j
          ON j.execution_id = t.id::text
          -- Or, if linked via payload id:
          -- ON j.job_payload->>'bulkExecutionId' = t.id::text
        CROSS JOIN LATERAL jsonb_array_elements(t.results) AS res
        LEFT  JOIN LATERAL (
          SELECT v.vrow
          FROM jsonb_array_elements(j.job_payload->'excelData'->'validRows') AS v(vrow)
          WHERE (v.vrow->>'originalRowIndex')::int = (res->>'rowIndex')::int
          LIMIT 1
        ) AS vr ON TRUE
        WHERE t.id = :bulkId
        ORDER BY row_index
        """
    )
    fun streamAllResponses(bulkId: UUID): Flow<RowBodyProjection>

    @Query(
        """
        SELECT job_payload
        FROM job_executions
        WHERE execution_id = :bulkId::text
        LIMIT 1
      """
    )
    suspend fun findJobPayloadByBulkId(bulkId: UUID): String?
}

data class RowBodyProjection(
    val testCaseId: String?,
    val rowIndex: Int,
    val body: String? // stringified JSON in your results; cast if you prefer jsonb->text
)

data class BulkExecutionRowProjection(
    val hasRequestBody: Boolean,
    val hasResponseBody: Boolean,
//    val requestBody: String,
//    val responseBody: String,
    val executionTimeMs: Int,
    val statusCode: Int,
    val rowIndex: Int,
    val error: String?,
    val success: Boolean?,
    val testCaseId: String?,
    val description: String?
)
