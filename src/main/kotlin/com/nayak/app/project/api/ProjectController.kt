package com.nayak.app.project.api

import com.fasterxml.jackson.databind.JsonNode
import com.nayak.app.bulk.app.BulkExecutionService
import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.project.app.ProjectService
import com.nayak.app.project.model.ProjectType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.*


@RestController
@RequestMapping("/api/projects")
@Tag(name = "Projects", description = "Project management endpoints")
@SecurityRequirement(name = "bearer-jwt")
class ProjectController(
    private val projectService: ProjectService,
    private val bulkExecutionService: BulkExecutionService
) {

    @PostMapping
    @Operation(summary = "Create a new project")
    suspend fun createProject(
        @Valid @RequestBody request: CreateProjectRequest,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<Any>> {
        return projectService.createProject(
            name = request.name,
            type = request.type,
            meta = request.meta,
            requestTemplate = request.requestTemplate,
            responseTemplate = request.responseTemplate,
            ownerId = userId
        ).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { project ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.success(project, "Project created successfully"))
            }
        )
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get project by ID")
    suspend fun getProject(
        @PathVariable id: UUID,
    ): ResponseEntity<ApiResponse<Any>> {
        return projectService.findProjectById(id).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { project ->
                ResponseEntity.ok(ApiResponse.success(project))
            }
        )
    }

    @GetMapping
    @Operation(summary = "Get all projects for authenticated user")
    suspend fun getProjects(
    ): ResponseEntity<ApiResponse<Any>> {
        return projectService.findAll().fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { projects ->
                ResponseEntity.ok(ApiResponse.success(projects))
            }
        )
    }

    @GetMapping("/{id}/excel-template")
    @Operation(summary = "Generate Excel template for bulk execution")
    suspend fun generateExcelTemplate(
        @PathVariable id: UUID,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ByteArray> {
        return bulkExecutionService.generateExcelTemplate(id, userId).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).build()
            },
            ifRight = { excelBytes ->
                ResponseEntity.ok()
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=template.xlsx")
                    .body(excelBytes)
            }
        )
    }
}

data class CreateProjectRequest(
    @field:NotBlank(message = "Project name cannot be blank")
    val name: String,

    @field:NotNull(message = "Project type cannot be null")
    val type: ProjectType,

    @field:NotNull(message = "Project metadata cannot be null")
    val meta: JsonNode,

    // Optional template fields for bulk operations
    val requestTemplate: JsonNode? = null,
    val responseTemplate: JsonNode? = null
)