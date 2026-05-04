package com.nayak.app.user.api

import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.http.toResponse
import com.nayak.app.user.app.PagedResult
import com.nayak.app.user.app.RoleOperation
import com.nayak.app.user.app.UserDto
import com.nayak.app.user.app.UserService
import com.nayak.app.user.domain.RoleFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse


@RestController
@RequestMapping("/api/users")
@Tag(name = "User Management", description = "User administration endpoints (Admin only)")
@SecurityRequirement(name = "bearer-jwt")
class UserController(private val userService: UserService) {

    @GetMapping("/{racfId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @Operation(
        summary = "Get user by RACF ID",
        description = "Retrieve user details by their RACF ID. Users can view their own profile, admins can view any user."
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "User retrieved successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = UserDto::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "id": "550e8400-e29b-41d4-a716-446655440000",
                                "racfId": "user123",
                                "roles": ["USER", "ADMIN"],
                                "isEnabled": true
                            },
                            "message": "User retrieved successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized"),
            SwaggerApiResponse(responseCode = "404", description = "User not found")
        ]
    )
    suspend fun getUserById(
        @Parameter(description = "RACF ID of the user", example = "user123", required = true)
        @PathVariable racfId: String
    ): ResponseEntity<ApiResponse<UserDto>> =
        userService.getUserByRacfId(racfId).toResponse("User retrieved successfully")

    @GetMapping("/search")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Search users with pagination",
        description = "Search and filter users with pagination support. Admin only."
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Users retrieved successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "content": [
                                    {
                                        "id": "550e8400-e29b-41d4-a716-446655440000",
                                        "racfId": "user123",
                                        "roles": ["USER"],
                                        "isEnabled": true
                                    }
                                ],
                                "page": 0,
                                "size": 20,
                                "totalElements": 1,
                                "totalPages": 1
                            },
                            "message": "Users retrieved successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized"),
            SwaggerApiResponse(responseCode = "403", description = "Forbidden - Admin role required")
        ]
    )
    suspend fun searchUsers(
        @Parameter(description = "Search by RACF ID (partial match)", example = "user")
        @RequestParam(required = false) racfId: String?,

        @Parameter(description = "Filter by role", example = "USER")
        @RequestParam(defaultValue = "ALL") roleFilter: RoleFilter,

        @Parameter(description = "Page number (0-based)", example = "0")
        @RequestParam(defaultValue = "0") @Min(0) page: Int,

        @Parameter(description = "Page size (1-100)", example = "20")
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
    ): ResponseEntity<ApiResponse<PagedResult<UserDto>>> =
        userService.searchUsers(racfId, roleFilter, page, size)
            .toResponse("Users retrieved successfully")

    @PatchMapping("/{racfId}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Enable user account",
        description = "Enable a disabled user account. Admin only."
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "User enabled successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": null,
                            "message": "User enabled successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized"),
            SwaggerApiResponse(responseCode = "403", description = "Forbidden - Admin role required"),
            SwaggerApiResponse(responseCode = "404", description = "User not found"),
            SwaggerApiResponse(responseCode = "409", description = "Conflict - User is already enabled")
        ]
    )
    suspend fun enableUser(
        @Parameter(description = "RACF ID of the user to enable", example = "user123", required = true)
        @PathVariable racfId: String
    ): ResponseEntity<ApiResponse<Unit>> =
        userService.enableUser(racfId).toResponse("User enabled successfully")

    @PatchMapping("/{racfId}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Disable user account",
        description = "Disable an active user account. Admin only."
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "User disabled successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": null,
                            "message": "User disabled successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized"),
            SwaggerApiResponse(responseCode = "403", description = "Forbidden - Admin role required"),
            SwaggerApiResponse(responseCode = "404", description = "User not found"),
            SwaggerApiResponse(responseCode = "409", description = "Conflict - User is already disabled")
        ]
    )
    suspend fun disableUser(
        @Parameter(description = "RACF ID of the user to disable", example = "user123", required = true)
        @PathVariable racfId: String
    ): ResponseEntity<ApiResponse<Unit>> =
        userService.disableUser(racfId).toResponse("User disabled successfully")

    @PatchMapping("/{racfId}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Update user roles",
        description = "Add or remove a role from a user. Admin only. Cannot remove USER role if it's the only role."
    )
    @SwaggerRequestBody(
        description = "Role update request",
        required = true,
        content = [Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = Schema(implementation = UpdateRoleRequest::class),
            examples = [
                ExampleObject(
                    name = "Add ADMIN role",
                    value = """{"role": "ADMIN", "operation": "ADD"}"""
                ),
                ExampleObject(
                    name = "Remove ADMIN role",
                    value = """{"role": "ADMIN", "operation": "REMOVE"}"""
                )
            ]
        )]
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "User role updated successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = UserDto::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "id": "550e8400-e29b-41d4-a716-446655440000",
                                "racfId": "user123",
                                "roles": ["USER", "ADMIN"],
                                "isEnabled": true
                            },
                            "message": "User role updated successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(responseCode = "400", description = "Bad request - validation failed"),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized"),
            SwaggerApiResponse(responseCode = "403", description = "Forbidden - Admin role required"),
            SwaggerApiResponse(responseCode = "404", description = "User not found"),
            SwaggerApiResponse(
                responseCode = "409",
                description = "Conflict - role already exists or cannot be removed"
            )
        ]
    )
    suspend fun updateUserRole(
        @Parameter(description = "RACF ID of the user", example = "user123", required = true)
        @PathVariable racfId: String,
        @Valid @RequestBody request: UpdateRoleRequest
    ): ResponseEntity<ApiResponse<UserDto>> =
        userService.updateUserRole(racfId, request.role, request.operation)
            .toResponse("User role updated successfully")

    @DeleteMapping("/{racfId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Delete user",
        description = "Permanently delete a user account. Admin only. This action cannot be undone."
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "User deleted successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": null,
                            "message": "User Deleted Successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(responseCode = "401", description = "Unauthorized"),
            SwaggerApiResponse(responseCode = "403", description = "Forbidden - Admin role required"),
            SwaggerApiResponse(responseCode = "404", description = "User not found")
        ]
    )
    suspend fun deleteUser(
        @Parameter(description = "RACF ID of the user to delete", example = "user123", required = true)
        @PathVariable racfId: String
    ): ResponseEntity<ApiResponse<Unit>> =
        userService.deleteUser(racfId).toResponse("User Deleted Successfully")
}

@Schema(description = "User role update request")
data class UpdateRoleRequest(
    @field:NotBlank(message = "Role cannot be blank")
    @field:Pattern(regexp = "USER|ADMIN", message = "Role must be either USER or ADMIN")
    @Schema(
        description = "Role to add or remove",
        example = "ADMIN",
        required = true,
        allowableValues = ["USER", "ADMIN"]
    )
    val role: String,

    @field:NotNull(message = "Operation cannot be null")
    @Schema(
        description = "Operation to perform (ADD or REMOVE)",
        example = "ADD",
        required = true,
        allowableValues = ["ADD", "REMOVE"]
    )
    val operation: RoleOperation
)
