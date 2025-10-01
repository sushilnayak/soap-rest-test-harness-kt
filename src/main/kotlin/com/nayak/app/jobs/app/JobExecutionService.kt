package com.nayak.app.jobs.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.common.errors.DomainError
import com.nayak.app.jobs.domain.JobExecution
import com.nayak.app.jobs.domain.JobProgressInfo
import com.nayak.app.jobs.domain.JobStatus
import com.nayak.app.jobs.domain.JobType
import com.nayak.app.jobs.repo.JobExecutionRepository
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.*

@Service
class JobExecutionService(
    private val jobExecutionRepository: JobExecutionRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(JobExecutionService::class.java)
    private val jobScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    suspend fun createJob(
        jobType: JobType,
        ownerId: String,
        payload: Any,
        maxRetries: Int = 3
    ): Either<DomainError, JobExecution> {
        return try {
            val executionId = generateExecutionId()
            val job = JobExecution(
                jobType = jobType,
                executionId = executionId,
                status = JobStatus.PENDING,
                ownerId = ownerId,
                jobPayload = objectMapper.valueToTree(payload),
                maxRetries = maxRetries
            )

            val savedJob = jobExecutionRepository.save(job)
            logger.info("Created job: type={}, executionId={}, ownerId={}", jobType, executionId, ownerId)
            savedJob.right()
        } catch (e: Exception) {
            logger.error("Failed to create job", e)
            DomainError.Database("Failed to create job: ${e.message}").left()
        }
    }

    fun executeJobAsync(job: JobExecution, executor: suspend (JobExecution) -> Unit) {
        jobScope.launch {
            executeJobWithRetry(job, executor)
        }
    }

    private suspend fun executeJobWithRetry(job: JobExecution, executor: suspend (JobExecution) -> Unit) {
        val executionId = job.executionId

        try {
            // Set up MDC for structured logging
            MDC.put("executionId", executionId)
            MDC.put("jobType", job.jobType.name)
            MDC.put("ownerId", job.ownerId)

            logger.info("Starting job execution: executionId={}", executionId)

            // Update job status to running
            val runningJob = job.copy(
                status = JobStatus.RUNNING,
                startedAt = LocalDateTime.now()
            )
            jobExecutionRepository.save(runningJob)

            // Execute the job
            executor(runningJob)

            // Mark as completed
            val completedJob = runningJob.copy(
                status = JobStatus.COMPLETED,
                completedAt = LocalDateTime.now()
            )
            jobExecutionRepository.save(completedJob)

            logger.info("Job completed successfully: executionId={}", executionId)

        } catch (e: Exception) {
            logger.error("Job execution failed: executionId={}", executionId, e)
            handleJobFailure(job, e)
        } finally {
            MDC.clear()
        }
    }

    private suspend fun handleJobFailure(job: JobExecution, error: Exception) {
        val newRetryCount = job.retryCount + 1

        if (newRetryCount <= job.maxRetries) {
            // Schedule retry with exponential backoff
            val backoffMinutes = calculateBackoffMinutes(newRetryCount)
            val nextRetryAt = LocalDateTime.now().plusMinutes(backoffMinutes)

            val retryJob = job.copy(
                status = JobStatus.RETRY_SCHEDULED,
                retryCount = newRetryCount,
                nextRetryAt = nextRetryAt,
                errorMessage = error.message,
                errorDetails = objectMapper.valueToTree(mapOf(
                    "exception" to error.javaClass.simpleName,
                    "message" to error.message,
                    "stackTrace" to error.stackTrace.take(10).map { it.toString() }
                ))
            )

            jobExecutionRepository.save(retryJob)
            logger.warn("Job scheduled for retry {}/{}: executionId={}, nextRetryAt={}",
                newRetryCount, job.maxRetries, job.executionId, nextRetryAt)
        } else {
            // Mark as permanently failed
            val failedJob = job.copy(
                status = JobStatus.FAILED,
                completedAt = LocalDateTime.now(),
                errorMessage = error.message,
                errorDetails = objectMapper.valueToTree(mapOf(
                    "exception" to error.javaClass.simpleName,
                    "message" to error.message,
                    "finalFailure" to true,
                    "totalRetries" to newRetryCount - 1
                ))
            )

            jobExecutionRepository.save(failedJob)
            logger.error("Job permanently failed after {} retries: executionId={}",
                job.maxRetries, job.executionId)
        }
    }

    suspend fun updateJobProgress(
        executionId: String,
        progressInfo: JobProgressInfo
    ): Either<DomainError, Unit> {
        return try {
            val job = jobExecutionRepository.findByExecutionId(executionId)
                ?: return DomainError.NotFound("Job not found").left()

            val updatedJob = job.copy(
                progressInfo = objectMapper.valueToTree(progressInfo),
                updatedAt = LocalDateTime.now()
            )

            jobExecutionRepository.save(updatedJob)

            // Log progress with structured data
            MDC.put("executionId", executionId)
            logger.info("Job progress updated: processed={}/{}, success={}, failed={}",
                progressInfo.processedItems, progressInfo.totalItems,
                progressInfo.successfulItems, progressInfo.failedItems)
            MDC.clear()

            Unit.right()
        } catch (e: Exception) {
            logger.error("Failed to update job progress: executionId={}", executionId, e)
            DomainError.Database("Failed to update job progress: ${e.message}").left()
        }
    }

    suspend fun cancelJob(executionId: String, ownerId: String): Either<DomainError, Unit> {
        return try {
            val job = jobExecutionRepository.findByExecutionId(executionId)
                ?: return DomainError.NotFound("Job not found").left()

            if (job.ownerId != ownerId) {
                return DomainError.Forbidden("Access denied").left()
            }

            if (job.status in listOf(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED)) {
                return DomainError.Validation("Job cannot be cancelled in current status: ${job.status}").left()
            }

            val cancelledJob = job.copy(
                status = JobStatus.CANCELLED,
                completedAt = LocalDateTime.now(),
                errorMessage = "Cancelled by user"
            )

            jobExecutionRepository.save(cancelledJob)
            logger.info("Job cancelled: executionId={}, ownerId={}", executionId, ownerId)

            Unit.right()
        } catch (e: Exception) {
            logger.error("Failed to cancel job: executionId={}", executionId, e)
            DomainError.Database("Failed to cancel job: ${e.message}").left()
        }
    }

    suspend fun getJobStatus(executionId: String, ownerId: String): Either<DomainError, JobExecution> {
        return try {
            val job = jobExecutionRepository.findByExecutionId(executionId)
                ?: return DomainError.NotFound("Job not found").left()

            if (job.ownerId != ownerId) {
                return DomainError.Forbidden("Access denied").left()
            }

            job.right()
        } catch (e: Exception) {
            logger.error("Failed to get job status: executionId={}", executionId, e)
            DomainError.Database("Failed to get job status: ${e.message}").left()
        }
    }

    suspend fun getJobsByOwner(ownerId: String, jobType: JobType? = null): Either<DomainError, List<JobExecution>> {
        return try {
            val jobs = if (jobType != null) {
                jobExecutionRepository.findByJobTypeAndOwnerIdOrderByCreatedAtDesc(jobType, ownerId)
            } else {
                jobExecutionRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
            }
            jobs.right()
        } catch (e: Exception) {
            logger.error("Failed to get jobs for owner: ownerId={}", ownerId, e)
            DomainError.Database("Failed to get jobs: ${e.message}").left()
        }
    }

    // Retry scheduler - should be called periodically
    suspend fun processRetryableJobs() {
        try {
            val retryableJobs = jobExecutionRepository.findByStatusAndNextRetryAtBefore(
                JobStatus.RETRY_SCHEDULED,
                LocalDateTime.now()
            )

            logger.info("Found {} jobs ready for retry", retryableJobs.size)

            retryableJobs.forEach { job ->
                val updatedJob = job.copy(status = JobStatus.PENDING)
                jobExecutionRepository.save(updatedJob)
                logger.info("Job reset to pending for retry: executionId={}", job.executionId)
            }
        } catch (e: Exception) {
            logger.error("Failed to process retryable jobs", e)
        }
    }

    private fun generateExecutionId(): String {
        return "exec_${UUID.randomUUID().toString().replace("-", "").take(12)}"
    }

    private fun calculateBackoffMinutes(retryCount: Int): Long {
        // Exponential backoff: 2^retryCount minutes, max 60 minutes
        return minOf(60L, (1L shl retryCount))
    }
}