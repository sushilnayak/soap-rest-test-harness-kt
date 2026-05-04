package com.nayak.app.project.api

import com.fasterxml.jackson.databind.JsonNode
import com.nayak.app.bulk.app.BulkExecutionService
import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.http.toResponse
import com.nayak.app.project.app.*
import com.nayak.app.project.model.Project
import com.nayak.app.project.model.ProjectType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@RestController
@RequestMapping("/api/projects")
@Tag(name = "Projects", description = "Project management endpoints for SOAP/REST test configurations")
@SecurityRequirement(name = "bearer-jwt")
class ProjectController(
    private val projectService: ProjectService,
    private val bulkExecutionService: BulkExecutionService
) {

    @PostMapping
    @Operation(
        summary = "Create a new project",
        description = "Create a new SOAP or REST project with configuration metadata, request/response templates"
    )
    @SwaggerRequestBody(
        description = "Project creation request",
        required = true,
        content = [Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = Schema(implementation = CreateProjectRequest::class),
            examples = [ExampleObject(
                name = "REST Project",
                value = """{
                    "name": "User API Tests",
                    "type": "REST",
                    "meta": {
                        "targetUrl": "https://api.example.com/users",
                        "method": "POST",
                        "headers": {
                            "Content-Type": "application/json"
                        }
                    },
                    "requestTemplate": {
                        "name": "{{userName}}",
                        "email": "{{userEmail}}"
                    }
                }"""
            )]
        )]
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Project created successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ProjectDto::class)
                )]
            ),
            SwaggerApiResponse(responseCode = "400", description = "Bad request - validation failed"),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
            SwaggerApiResponse(responseCode = "409", description = "Conflict - project name already exists")
        ]
    )
    suspend fun createProject(
        @Valid @RequestBody request: CreateProjectRequest,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<ProjectDto>> =
        projectService.createProject(
            name = request.name,
            type = request.type,
            meta = request.meta,
            requestTemplate = request.requestTemplate,
            responseTemplate = request.responseTemplate,
            ownerId = userId
        ).toResponse("Project created successfully")

    @GetMapping("/{id}")
    @Operation(
        summary = "Get project by ID",
        description = "Retrieve complete project details including metadata and templates"
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Project retrieved successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = Project::class)
                )]
            ),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
            SwaggerApiResponse(responseCode = "404", description = "Project not found")
        ]
    )
    suspend fun getProject(
        @Parameter(description = "Project ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
        @PathVariable id: UUID,
    ): ResponseEntity<ApiResponse<Project>> =
        projectService.findProjectById(id).toResponse()

    @GetMapping
    @Operation(
        summary = "Get all projects with pagination",
        description = "Retrieve all projects with optional filtering by type and search term, with pagination support"
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Projects retrieved successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "content": [
                                    {
                                        "id": "550e8400-e29b-41d4-a716-446655440000",
                                        "name": "User API Tests",
                                        "targetUrl": "https://api.example.com/users",
                                        "type": "REST",
                                        "ownerId": "user123",
                                        "createdAt": "2024-01-15T10:00:00",
                                        "updatedAt": "2024-01-15T10:00:00"
                                    }
                                ],
                                "page": 0,
                                "size": 20,
                                "totalElements": 1,
                                "totalPages": 1
                            },
                            "message": "Projects retrieved successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(responseCode = "400", description = "Bad request - invalid pagination parameters"),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token")
        ]
    )
    suspend fun getAllProjects(
        @Parameter(description = "Page number (0-based)", example = "0")
        @RequestParam(defaultValue = "0") @Min(0) page: Int,

        @Parameter(description = "Filter by project type", example = "REST")
        @RequestParam(required = false) type: ProjectType?,

        @Parameter(description = "Search by project name (partial match)", example = "User")
        @RequestParam(required = false) search: String?,

        @Parameter(description = "Page size (1-100)", example = "20")
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
    ): ResponseEntity<ApiResponse<PagedResult<ProjectViewDto>>> =
        projectService.findAllPaginated(type = type, search = search, page = page, size = size)
            .toResponse("Projects retrieved successfully")

    @GetMapping("/projects-with-ids")
    @Operation(
        summary = "Get project names and IDs",
        description = "Retrieve a lightweight list of all projects with only ID and name (useful for dropdowns)"
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Project list retrieved successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": [
                                {
                                    "id": "550e8400-e29b-41d4-a716-446655440000",
                                    "name": "User API Tests"
                                }
                            ],
                            "message": null,
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token")
        ]
    )
    suspend fun getProjectWithNameAndIds(): ResponseEntity<ApiResponse<List<ProjectWithNameAndId>>> =
        projectService.findProjectWithNameIds().toResponse()

    @PutMapping("/{id}")
    @Operation(
        summary = "Update project",
        description = "Update an existing project's name, metadata, or templates. Only provided fields will be updated."
    )
    @SwaggerRequestBody(
        description = "Project update request (all fields optional)",
        required = true,
        content = [Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = Schema(implementation = UpdateProjectRequest::class),
            examples = [ExampleObject(
                value = """{
                    "name": "Updated User API Tests",
                    "meta": {
                        "targetUrl": "https://api.example.com/v2/users"
                    }
                }"""
            )]
        )]
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Project updated successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ProjectDto::class)
                )]
            ),
            SwaggerApiResponse(responseCode = "400", description = "Bad request - validation failed"),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
            SwaggerApiResponse(responseCode = "404", description = "Project not found"),
            SwaggerApiResponse(responseCode = "409", description = "Conflict - project name already exists")
        ]
    )
    suspend fun updateProject(
        @Parameter(description = "Project ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateProjectRequest,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<ProjectDto>> =
        projectService.updateProject(
            projectId = id,
            name = request.name,
            meta = request.meta,
            requestTemplate = request.requestTemplate,
            responseTemplate = request.responseTemplate,
            updaterId = userId
        ).toResponse("Project updated successfully")

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Delete project",
        description = "Permanently delete a project. Admin only. This action cannot be undone."
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Project deleted successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": null,
                            "message": "Project deleted successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
            SwaggerApiResponse(responseCode = "403", description = "Forbidden - Admin role required"),
            SwaggerApiResponse(responseCode = "404", description = "Project not found")
        ]
    )
    suspend fun deleteProject(
        @Parameter(
            description = "Project ID to delete",
            example = "550e8400-e29b-41d4-a716-446655440000",
            required = true
        )
        @PathVariable id: UUID
    ): ResponseEntity<ApiResponse<Unit>> =
        projectService.deleteProject(id).toResponse("Project deleted successfully")

    @GetMapping("/{id}/excel-template")
    @Operation(
        summary = "Generate Excel template for bulk execution",
        description = "Generate an Excel template file based on the project's request template for bulk test execution"
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Excel template generated successfully",
                content = [Content(
                    mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )]
            ),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized - invalid or missing JWT token"),
            SwaggerApiResponse(responseCode = "404", description = "Project not found"),
            SwaggerApiResponse(responseCode = "500", description = "Failed to generate Excel template")
        ]
    )
    suspend fun generateExcelTemplate(
        @Parameter(description = "Project ID", example = "550e8400-e29b-41d4-a716-446655440000", required = true)
        @PathVariable id: UUID
    ): ResponseEntity<ByteArray> {
        return bulkExecutionService.generateExcelTemplate(id, true).fold(
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

@Schema(description = "Project creation request")
data class CreateProjectRequest(
    @field:NotBlank(message = "Project name cannot be blank")
    @Schema(
        description = "Unique project name",
        example = "User API Tests",
        required = true
    )
    val name: String,

    @field:NotNull(message = "Project type cannot be null")
    @Schema(
        description = "Type of project (SOAP or REST)",
        example = "REST",
        required = true,
        allowableValues = ["SOAP", "REST"]
    )
    val type: ProjectType,

    @field:NotNull(message = "Project metadata cannot be null")
    @Schema(
        description = "Project metadata including target URL, headers, etc.",
        example = """{"targetUrl": "https://api.example.com/users", "method": "POST"}""",
        required = true
    )
    val meta: JsonNode,

    @Schema(
        description = "Request template with placeholders (e.g., {{userName}})",
        example = """{"name": "{{userName}}", "email": "{{userEmail}}"}"""
    )
    val requestTemplate: JsonNode? = null,

    @Schema(
        description = "Expected response template for validation",
        example = """{"id": "{{userId}}", "status": "created"}"""
    )
    val responseTemplate: JsonNode? = null
)

@Schema(description = "Project update request (all fields optional)")
data class UpdateProjectRequest(
    @Schema(
        description = "Updated project name",
        example = "Updated User API Tests"
    )
    val name: String?,

    @Schema(
        description = "Updated project metadata",
        example = """{"targetUrl": "https://api.example.com/v2/users"}"""
    )
    val meta: JsonNode?,

    @Schema(
        description = "Updated request template"
    )
    val requestTemplate: JsonNode?,

    @Schema(
        description = "Updated response template"
    )
    val responseTemplate: JsonNode?
)
