package com.nayak.app.bulk.repo

import com.nayak.app.bulk.domain.BulkExecution
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface BulkExecutionRepository : CoroutineCrudRepository<BulkExecution, UUID>{

}