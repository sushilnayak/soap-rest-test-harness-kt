package com.nayak.app.user.api

import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.http.toResponse
import com.nayak.app.user.app.AuthService
import com.nayak.app.user.app.TokenResponse
import com.nayak.app.user.app.UserDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse


@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "User authentication and registration endpoints")
class AuthController(private val authService: AuthService) {

    @PostMapping("/signup")
    @Operation(
        summary = "Register a new user",
        description = "Create a new user account with RACF ID and password. Returns a JWT token upon successful registration."
    )
    @SwaggerRequestBody(
        description = "User registration details",
        required = true,
        content = [Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = Schema(implementation = SignupRequest::class),
            examples = [ExampleObject(
                name = "Valid signup",
                value = """{"racfId": "user123", "password": "dummy"}"""
            )]
        )]
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "User registered successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = TokenResponse::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                "expiresIn": 3600,
                                "tokenType": "Bearer"
                            },
                            "message": null,
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "400",
                description = "Invalid request - validation failed",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Racf ID cannot be blank",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "409",
                description = "Conflict - user already exists",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "User with racfId 'user123' already exists",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "500",
                description = "Internal server error",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Failed to create user: Database connection error",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            )
        ]
    )
    suspend fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<ApiResponse<TokenResponse>> =
        authService.signup(request.racfId, request.password).toResponse()

    @PostMapping("/login")
    @Operation(
        summary = "Authenticate user",
        description = "Login with RACF ID and password. Returns a JWT token upon successful authentication."
    )
    @SwaggerRequestBody(
        description = "User login credentials",
        required = true,
        content = [Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = Schema(implementation = LoginRequest::class),
            examples = [ExampleObject(
                name = "Valid login",
                value = """{"racfId": "user123", "password": "SecurePass123!"}"""
            )]
        )]
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Login successful",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = TokenResponse::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
                                "expiresIn": 3600,
                                "tokenType": "Bearer"
                            },
                            "message": null,
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "400",
                description = "Invalid request - validation failed",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Racf ID cannot be blank",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "401",
                description = "Authentication failed - invalid credentials",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Invalid credential",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "404",
                description = "User not found",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "User not found with user123",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            )
        ]
    )
    suspend fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<ApiResponse<TokenResponse>> =
        authService.login(request.racfId, request.password).toResponse()

    @GetMapping("/me")
    @Operation(
        summary = "Get current user profile",
        description = "Retrieve the profile information of the currently authenticated user"
    )
    @SecurityRequirement(name = "bearer-jwt")
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "User profile retrieved successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = UserDto::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "id": "550e8400-e29b-41d4-a716-446655440000",
                                "racfId": "user123",
                                "roles": ["USER"],
                                "isEnabled": true
                            },
                            "message": null,
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "401",
                description = "Unauthorized - invalid or missing token",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Invalid RacfId",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "404",
                description = "User not found",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "User not found - user123",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            )
        ]
    )
    suspend fun me(@AuthenticationPrincipal racfId: String?): ResponseEntity<ApiResponse<UserDto>> =
        authService.me(racfId).toResponse()
}

@Schema(description = "User registration request")
data class SignupRequest(
    @field:NotBlank(message = "Racf ID cannot be blank")
    @field:Size(min = 3, max = 50, message = "Racf ID must be between 3 and 50 characters")
    @Schema(
        description = "Unique RACF identifier for the user",
        example = "user123",
        required = true,
        minLength = 3,
        maxLength = 50
    )
    val racfId: String,

    @field:NotBlank(message = "Password cannot be blank")
    @field:Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    @Schema(
        description = "User password (minimum 8 characters)",
        example = "SecurePass123!",
        required = true,
        minLength = 8,
        maxLength = 100,
        format = "password"
    )
    val password: String
)

@Schema(description = "User login request")
data class LoginRequest(
    @field:NotBlank(message = "Racf ID cannot be blank")
    @Schema(
        description = "RACF identifier",
        example = "user123",
        required = true
    )
    val racfId: String,

    @field:NotBlank(message = "Password cannot be blank")
    @Schema(
        description = "User password",
        example = "SecurePass123!",
        required = true,
        format = "password"
    )
    val password: String
)
