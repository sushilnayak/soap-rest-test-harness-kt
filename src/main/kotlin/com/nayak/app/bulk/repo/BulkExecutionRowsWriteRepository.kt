package com.nayak.app.bulk.repo

import com.nayak.app.bulk.model.BulkExecutionRowsEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface BulkExecutionRowsWriteRepository : CoroutineCrudRepository<BulkExecutionRowsEntity, UUID> {

    @Query(
        """
        INSERT INTO public.bulk_execution_rows (bulk_execution_id, row_index, test_case_id, description)
        VALUES (:bulkId, :rowIndex, :testCaseId, :description)
        ON CONFLICT (bulk_execution_id, row_index) DO UPDATE SET
          test_case_id = EXCLUDED.test_case_id,
          description  = EXCLUDED.description
    """
    )
    suspend fun upsertRowHeader(
        bulkId: UUID,
        rowIndex: Int,
        testCaseId: String?,
        description: String?
    ): Int
}
