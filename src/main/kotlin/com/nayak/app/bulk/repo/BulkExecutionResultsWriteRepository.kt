package com.nayak.app.bulk.repo

import com.nayak.app.bulk.model.BulkExecutionResultsEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface BulkExecutionResultsWriteRepository : CoroutineCrudRepository<BulkExecutionResultsEntity, UUID> {

    @Query(
        """
        INSERT INTO public.bulk_execution_results (
            bulk_execution_id, row_index, test_case_id, description, request_body, created_at
        )
        VALUES (:bulkId, :rowIndex, :testCaseId, :description, CAST(:requestBody AS jsonb), NOW())
        ON CONFLICT (bulk_execution_id, row_index) DO UPDATE SET
            test_case_id   = EXCLUDED.test_case_id,
            description    = EXCLUDED.description,
            request_body   = EXCLUDED.request_body
    """
    )
    suspend fun upsertRequest(
        bulkId: UUID,
        rowIndex: Int,
        testCaseId: String?,
        description: String?,
        requestBody: String? // pass compact JSON string or null
    ): Int

    @Query(
        """
        UPDATE public.bulk_execution_results
        SET response_body     = CAST(:responseBody AS jsonb),
            status_code       = :statusCode,
            success           = :success,
            error             = :error,
            execution_time_ms = :executionTimeMs
        WHERE bulk_execution_id = :bulkId AND row_index = :rowIndex
    """
    )
    suspend fun patchWithResponse(
        bulkId: UUID,
        rowIndex: Int,
        responseBody: String?, // compact JSON or null
        statusCode: Int?,
        success: Boolean?,
        error: String?,
        executionTimeMs: Int?
    ): Int

    @Query(
        """
        INSERT INTO public.bulk_execution_results (
          bulk_execution_id, row_index, test_case_id, description, success, error, execution_time_ms, created_at
        )
        VALUES (:bulkId, :rowIndex, :testCaseId, :description, :success, :error, :executionTimeMs, NOW())
        ON CONFLICT (bulk_execution_id, row_index) DO UPDATE SET
          success           = EXCLUDED.success,
          error             = EXCLUDED.error,
          execution_time_ms = EXCLUDED.execution_time_ms
    """
    )
    suspend fun upsertFailure(
        bulkId: UUID,
        rowIndex: Int,
        testCaseId: String?,
        description: String?,
        success: Boolean,
        error: String,
        executionTimeMs: Int?
    ): Int
}
