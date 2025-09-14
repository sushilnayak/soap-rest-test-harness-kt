package com.nayak.app.project.api

import com.fasterxml.jackson.databind.JsonNode
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
@Tag(name = "Projects", description = "Api Related to Projects")
@SecurityRequirement(name = "bearer-jwt")
class ProjectController(private val projectService: ProjectService) {

    @GetMapping
    @Operation(summary = "Get all projects")
    suspend fun projects(): ResponseEntity<Any> {
        return projectService.findAll().fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { projects ->
                ResponseEntity.ok(ApiResponse.success(projects))
            }
        )
    }

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

    @Operation(summary = "Get project by ID")
    @GetMapping("/{id}")
    suspend fun project(@PathVariable id: UUID): ResponseEntity<Any> {
        return projectService.findByProjectId(id).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { projects -> ResponseEntity.ok(ApiResponse.success(data = projects)) }
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