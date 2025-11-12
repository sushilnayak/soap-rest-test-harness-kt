package com.nayak.app.common.service

// src/main/kotlin/.../CleanupService.kt
import com.nayak.app.bulk.repo.BulkExecutionRepository
import com.nayak.app.common.config.CleanupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.*

@Service
@EnableConfigurationProperties(CleanupProperties::class)
class CleanupService(
    private val props: CleanupProperties,
    private val maintenanceRepo: BulkExecutionRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Wire the cron / zone from properties
    @Scheduled(cron = "\${cleanup.cron}", zone = "\${cleanup.zone}")
    suspend fun scheduledCleanup() {
        if (!props.enabled) {
            logger.info("Cleanup disabled. Skipping run.")
            return
        }
        val cutoff = OffsetDateTime.now(ZoneId.of(props.zone))
            .minusDays(props.retentionDays)

        withContext(Dispatchers.IO) {
            var totalDeleted = 0
            var batch = 0

            while (true) {
                val ids: List<UUID> = maintenanceRepo
                    .findOldExecutionIds(cutoff, props.batchSize)
                    .toList()

                if (ids.isEmpty()) break
                batch++

                var deletedInBatch = 0
                for (id in ids) {
                    // deleteById triggers cascade to job_executions, bulk_execution_results, bulk_execution_rows
                    runCatching { maintenanceRepo.deleteById(id) }
                        .onSuccess { deletedInBatch++ }
                        .onFailure { ex ->
                            logger.error("Failed to delete execution id={} : {}", id, ex.message, ex)
                        }
                }
                totalDeleted += deletedInBatch

                logger.info(
                    "Cleanup batch #{}: requested={} deleted={} cutoff={}",
                    batch, ids.size, deletedInBatch, cutoff
                )

                if (ids.size < props.batchSize) break
            }

            logger.info(
                "Cleanup finished. totalDeleted={} retentionDays={} cutoff={}",
                totalDeleted, props.retentionDays, cutoff
            )
        }
    }
}
