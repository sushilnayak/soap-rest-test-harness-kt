package com.nayak.app.bulk.repo

import com.nayak.app.bulk.domain.BulkExecution
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
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

}
