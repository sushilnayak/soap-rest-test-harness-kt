package com.nayak.app.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.*

//@ConstructorBinding constructor
@ConfigurationProperties("cleanup")
data class CleanupProperties(
    val enabled: Boolean = true,
    val retentionDays: Long = 15,
    val batchSize: Int = 500,
    val cron: String = "0 30 2 * * *",
    val zone: String = TimeZone.getTimeZone("UTC").displayName,
)
