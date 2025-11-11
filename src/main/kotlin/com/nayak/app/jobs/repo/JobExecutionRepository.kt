package com.nayak.app.jobs.repo

import com.nayak.app.jobs.domain.JobExecution
import com.nayak.app.jobs.domain.JobStatus
import com.nayak.app.jobs.domain.JobType
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.*

@Repository
interface JobExecutionRepository : CoroutineCrudRepository<JobExecution, UUID> {
    suspend fun findByExecutionId(executionId: String): JobExecution?
    suspend fun findByOwnerIdOrderByCreatedAtDesc(ownerId: String): List<JobExecution>
    suspend fun findByStatusAndNextRetryAtBefore(status: JobStatus, dateTime: LocalDateTime): List<JobExecution>
    suspend fun findByStatusIn(statuses: List<JobStatus>): List<JobExecution>
    suspend fun findByJobTypeAndOwnerIdOrderByCreatedAtDesc(jobType: JobType, ownerId: String): List<JobExecution>

    suspend fun deleteJobExecutionByExecutionId(executionId: String): Int
}