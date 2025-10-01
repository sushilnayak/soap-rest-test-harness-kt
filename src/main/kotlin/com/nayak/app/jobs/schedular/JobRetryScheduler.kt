package com.nayak.app.jobs.schedular

import com.nayak.app.jobs.app.JobExecutionService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class JobRetryScheduler(private val jobExecutionService: JobExecutionService) {
    private val logger = LoggerFactory.getLogger(JobRetryScheduler::class.java)

    @Scheduled(fixedDelay = 60000) // Run every minute
    fun processRetryableJobs() {
        runBlocking {
            try {
                jobExecutionService.processRetryableJobs()
            } catch (e: Exception) {
                logger.error("Error processing retryable jobs", e)
            }
        }
    }
}