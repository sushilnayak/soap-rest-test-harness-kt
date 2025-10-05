package com.nayak.app.project.api

import com.fasterxml.jackson.databind.JsonNode
import com.nayak.app.bulk.app.BulkExecutionService
import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.project.app.ProjectService
import com.nayak.app.project.model.ProjectFilter
import com.nayak.app.project.model.ProjectType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
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

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Delete project",
        description = "Soft delete a project (Admin only)"
    )
    suspend fun deleteProject(
        @PathVariable id: UUID
    ): ResponseEntity<ApiResponse<Unit>> {
        return projectService.deleteProject(id).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error(error.message))
            },
            ifRight = {
                ResponseEntity.ok(ApiResponse.success(Unit, "Project deleted successfully"))
            }
        )
    }

    @PutMapping("/{id}")
    @Operation(
        summary = "Update project",
        description = "Update an existing project"
    )
    suspend fun updateProject(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateProjectRequest,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<Any>> {
        return projectService.updateProject(
            projectId = id,
            name = request.name,
            meta = request.meta,
            requestTemplate = request.requestTemplate,
            responseTemplate = request.responseTemplate,
            updaterId = userId
        ).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { project ->
                ResponseEntity.ok(ApiResponse.success(project, "Project updated successfully"))
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
    @Operation(
        summary = "Get all projects with pagination",
        description = "Retrieve all projects with pagination support"
    )
    suspend fun getAllProjects(
        @Parameter(description = "Page number (0-based)")
        @RequestParam(defaultValue = "0") @Min(0) page: Int,

        @Parameter(description = "Page size (1-100)")
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
    ): ResponseEntity<ApiResponse<Any>> {
        return projectService.findAllPaginated(page, size).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { pagedResult ->
                ResponseEntity.ok(ApiResponse.success(pagedResult, "Projects retrieved successfully"))
            }
        )
    }

    @GetMapping("/search")
    @Operation(
        summary = "Search projects",
        description = "Search projects with multiple filters and pagination"
    )
    suspend fun searchProjects(
        @Parameter(description = "Search by project name (partial match)")
        @RequestParam(required = false) name: String?,

        @Parameter(description = "Filter by project type (SOAP or REST)")
        @RequestParam(required = false) type: ProjectType?,

        @Parameter(description = "Filter by owner ID")
        @RequestParam(required = false) ownerId: String?,

        @Parameter(description = "Filter by status: ALL, ACTIVE, INACTIVE, MY_PROJECTS")
        @RequestParam(defaultValue = "ALL") filter: ProjectFilter,

        @Parameter(description = "Page number (0-based)")
        @RequestParam(defaultValue = "0") @Min(0) page: Int,

        @Parameter(description = "Page size (1-100)")
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
    ): ResponseEntity<ApiResponse<Any>> {
        return projectService.searchProjects(
            name = name,
            type = type,
            ownerId = ownerId,
            filter = filter,
            page = page,
            size = size
        ).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { pagedResult ->
                ResponseEntity.ok(ApiResponse.success(pagedResult, "Projects found successfully"))
            }
        )
    }

    @GetMapping("/autocomplete")
    @Operation(
        summary = "Autocomplete project names",
        description = "Get project name suggestions for autocomplete (returns top 10 matches by default)"
    )
    suspend fun autocompleteProjects(
        @Parameter(description = "Search query for autocomplete", required = true)
        @RequestParam query: String,

        @Parameter(description = "Maximum number of results (1-50)")
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) limit: Int
    ): ResponseEntity<ApiResponse<Any>> {
        return projectService.autocompleteProjects(query, limit).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { suggestions ->
                ResponseEntity.ok(ApiResponse.success(suggestions, "Autocomplete results retrieved"))
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

    val requestTemplate: JsonNode? = null,
    val responseTemplate: JsonNode? = null
)

data class UpdateProjectRequest(
    val name: String?,
    val meta: JsonNode?,
    val requestTemplate: JsonNode?,
    val responseTemplate: JsonNode?
)