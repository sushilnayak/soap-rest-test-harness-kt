package com.nayak.app.user.api

import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.user.app.RoleOperation
import com.nayak.app.user.app.UserService
import com.nayak.app.user.domain.RoleFilter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
@Tag(name = "Authentication", description = "User Authentication Endpoint")
@SecurityRequirement(name = "Bearer Authentication")
class UserController(private val userService: UserService) {

    @DeleteMapping("/{racfId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Delete User",
        description = "Delete user ( admin only )",
        tags = ["User Management"]
    )
    suspend fun deleteUser(@PathVariable racfId: String): ResponseEntity<ApiResponse<Unit>> {
        return userService.deleteUser(racfId).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Unit>(error.message))
            },
            ifRight = { ResponseEntity.ok(ApiResponse.success(Unit, "User Deleted Successfully")) }
        )
    }

    @PatchMapping("/users/{userId}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Enable user",
        description = "Enable a disabled user account (Admin only)",
        tags = ["User Management"]
    )
    suspend fun enableUser(@PathVariable racfId: String): ResponseEntity<ApiResponse<Any>> {
        return userService.enableUser(racfId).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { user ->
                ResponseEntity.ok(ApiResponse.success(user, "User enabled successfully"))
            }
        )
    }

    @PatchMapping("/users/{userId}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Disable user",
        description = "Disable a user account (Admin only)",
        tags = ["User Management"]
    )
    suspend fun disableUser(@PathVariable racfId: String): ResponseEntity<ApiResponse<Any>> {
        return userService.disableUser(racfId).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { user ->
                ResponseEntity.ok(ApiResponse.success(user, "User disabled successfully"))
            }
        )
    }

    @PatchMapping("/users/{userId}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Update user role",
        description = "Add or remove a role from user (Admin only)",
        tags = ["User Management"]
    )
    suspend fun updateUserRole(
        @PathVariable racfId: String,
        @Valid @RequestBody request: UpdateRoleRequest
    ): ResponseEntity<ApiResponse<Any>> {
        return userService.updateUserRole(racfId, request.role, request.operation).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { user ->
                ResponseEntity.ok(ApiResponse.success(user, "User role updated successfully"))
            }
        )
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Get user by ID",
        description = "Retrieve user details by ID",
        tags = ["User Management"]
    )
    suspend fun getUserById(@PathVariable racfId: String): ResponseEntity<ApiResponse<Any>> {
        return userService.getUserByRacfId(racfId).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { user ->
                ResponseEntity.ok(ApiResponse.success(user, "User retrieved successfully"))
            }
        )
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
        summary = "Search users",
        description = "Search and filter users with pagination (Admin only)",
        tags = ["User Management"]
    )
    suspend fun searchUsers(
        @Parameter(description = "Search by RACF ID (partial match)")
        @RequestParam(required = false) racfId: String?,

        @Parameter(description = "Filter by role: ALL, USER, or ADMIN")
        @RequestParam(defaultValue = "ALL") roleFilter: RoleFilter,

        @Parameter(description = "Page number (0-based)")
        @RequestParam(defaultValue = "0") @Min(0) page: Int,

        @Parameter(description = "Page size (1-100)")
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int
    ): ResponseEntity<ApiResponse<Any>> {
        return userService.searchUsers(racfId, roleFilter, page, size).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { pagedResult ->
                ResponseEntity.ok(ApiResponse.success(pagedResult, "Users retrieved successfully"))
            }
        )
    }
}


data class UpdateRoleRequest(
    @field:NotBlank(message = "Role cannot be blank")
    @field:Pattern(regexp = "USER|ADMIN", message = "Role must be either USER or ADMIN")
    val role: String,

    val operation: RoleOperation
)