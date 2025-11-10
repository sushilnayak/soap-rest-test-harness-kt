package com.nayak.app.project.model

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime
import java.util.*

@Table("projects")
data class Project(
    @Id val id: UUID? = null,

    val name: String,

    val meta: JsonNode,
    val type: ProjectType = ProjectType.SOAP,

    // Template fields for bulk operations
    @Column("request_template")
    val requestTemplate: JsonNode? = null,

    @Column("response_template")
    val responseTemplate: JsonNode? = null,

    @Column("owner_id") val ownerId: String,
    @Column("created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column("updated_at") val updatedAt: LocalDateTime = LocalDateTime.now(),
)

enum class ProjectType {
    SOAP, REST
}