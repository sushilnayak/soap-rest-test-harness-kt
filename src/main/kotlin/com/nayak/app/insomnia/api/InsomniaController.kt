package com.nayak.app.insomnia.api

import com.fasterxml.jackson.databind.JsonNode
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.http.toResponse
import com.nayak.app.insomnia.service.ExecutionService
import com.nayak.app.project.model.ProjectType
import io.swagger.v3.oas.annotations.Operation
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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@RestController
@RequestMapping("/api/execution/execute")
@Tag(
    name = "Request Execution",
    description = "Execute dynamic HTTP requests to downstream services (Insomnia-style interface)"
)
@SecurityRequirement(name = "bearer-jwt")
class InsomniaController(private val executionService: ExecutionService) {

    @PostMapping
    @Operation(
        summary = "Execute dynamic HTTP request",
        description = "Execute a dynamic HTTP request to a downstream service with support for SOAP/REST, authentication, retries, and custom headers"
    )
    @SwaggerRequestBody(
        description = "Request execution configuration",
        required = true,
        content = [Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = Schema(implementation = ExecutionRequest::class),
            examples = [
                ExampleObject(
                    name = "REST GET Request",
                    value = """{
                        "targetUrl": "https://api.example.com/users/123",
                        "httpMethod": "GET",
                        "requestType": "REST",
                        "headers": {
                            "Accept": "application/json"
                        },
                        "timeoutSeconds": 30
                    }"""
                ),
                ExampleObject(
                    name = "REST POST with Body",
                    value = """{
                        "targetUrl": "https://api.example.com/users",
                        "httpMethod": "POST",
                        "requestType": "REST",
                        "headers": {
                            "Content-Type": "application/json"
                        },
                        "requestBody": {
                            "name": "John Doe",
                            "email": "john@example.com"
                        },
                        "timeoutSeconds": 30
                    }"""
                ),
                ExampleObject(
                    name = "SOAP Request",
                    value = """{
                        "targetUrl": "https://soap.example.com/service",
                        "httpMethod": "POST",
                        "requestType": "SOAP",
                        "headers": {
                            "Content-Type": "text/xml; charset=utf-8",
                            "SOAPAction": "http://example.com/GetUser"
                        },
                        "requestBody": "<soap:Envelope>...</soap:Envelope>",
                        "timeoutSeconds": 60
                    }"""
                ),
                ExampleObject(
                    name = "REST POST with Authentication",
                    description = "POST request with OAuth2 authentication configuration",
                    value = """{
                        "targetUrl": "https://api.example.com/protected/resource",
                        "httpMethod": "POST",
                        "requestType": "REST",
                        "headers": {
                            "Content-Type": "application/json",
                            "Accept": "application/json"
                        },
                        "requestBody": {
                            "data": "sensitive information",
                            "userId": "12345"
                        },
                        "authConfig": {
                            "requiresAuth": true,
                            "authTokenUrl": "https://auth.example.com/oauth/token",
                            "authHeaderKey": "Authorization",
                            "authResponseAttribute": "access_token",
                            "authPayload": "{\"client_id\":\"my-client-id\",\"client_secret\":\"my-secret\",\"grant_type\":\"client_credentials\",\"aud\":\"https://api.example.com\"}",
                            "audience": "https://api.example.com"
                        },
                        "timeoutSeconds": 30,
                        "retryConfig": {
                            "maxAttempts": 3,
                            "backoffDelayMs": 1000,
                            "retryOnStatusCodes": [500, 502, 503, 504]
                        }
                    }"""
                )
            ]
        )]
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Request executed successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = ExecutionResponse::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "success": true,
                                "statusCode": 200,
                                "headers": {
                                    "Content-Type": ["application/json"],
                                    "Content-Length": ["123"]
                                },
                                "requestBody": "{\"name\":\"John\"}",
                                "responseBody": "{\"id\":123,\"name\":\"John\"}",
                                "executionTimeMs": 245,
                                "error": null,
                                "retryAttempts": 0
                            },
                            "message": "Request executed successfully",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "400",
                description = "Bad request - validation failed",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Target URL cannot be blank",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "401",
                description = "Unauthorized - invalid or missing JWT token",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Unauthorized",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "500",
                description = "Execution failed",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Connection timeout after 30 seconds",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            )
        ]
    )
    suspend fun makeRequest(@Valid @RequestBody request: ExecutionRequest): ResponseEntity<ApiResponse<ExecutionResponse>> =
        executionService.executeRequest(request).toResponse("Request executed successfully")
}

@Schema(description = "HTTP request execution configuration")
data class ExecutionRequest(
    @field:NotBlank(message = "Target URL cannot be blank")
    @Schema(
        description = "Target URL for the HTTP request",
        example = "https://api.example.com/users",
        required = true
    )
    val targetUrl: String,

    @field:NotNull(message = "HTTP method cannot be null")
    @Schema(
        description = "HTTP method to use",
        example = "POST",
        required = true,
        allowableValues = ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"]
    )
    val httpMethod: HttpMethod,

    @field:NotNull(message = "Request type cannot be null")
    @Schema(
        description = "Type of request (SOAP or REST)",
        example = "REST",
        required = true,
        allowableValues = ["SOAP", "REST"]
    )
    val requestType: ProjectType,

    @Schema(
        description = "HTTP headers to include in the request",
        example = """{"Content-Type": "application/json", "Accept": "application/json"}"""
    )
    val headers: Map<String, String> = emptyMap(),

    @Schema(
        description = "Request body (JSON for REST, XML string for SOAP)",
        example = """{"name": "John Doe", "email": "john@example.com"}"""
    )
    val requestBody: JsonNode? = null,

    @Schema(
        description = "Query parameters to append to the URL",
        example = """{"page": "1", "size": "20"}"""
    )
    val queryParams: Map<String, String> = emptyMap(),

    @Schema(description = "Authentication configuration (optional)")
    val authConfig: AuthConfig? = null,

    @field:Min(1, message = "Timeout must be at least 1 second")
    @field:Max(300, message = "Timeout cannot exceed 300 seconds")
    @Schema(
        description = "Request timeout in seconds",
        example = "30",
        minimum = "1",
        maximum = "300",
        defaultValue = "30"
    )
    val timeoutSeconds: Int = 30,

    @Schema(description = "Retry configuration (optional)")
    val retryConfig: RetryConfig? = null
)

@Schema(description = "Authentication configuration for requests requiring auth tokens")
data class AuthConfig(
    @Schema(description = "Header key for the auth token", example = "Authorization")
    val authHeaderKey: String,

    @Schema(description = "JSON path to extract token from auth response", example = "access_token")
    val authResponseAttribute: String,

    @Schema(
        description = "Authentication payload (JSON string)",
        example = """{"client_id":"abc","client_secret":"xyz"}"""
    )
    val authPayload: String,

    @Schema(description = "URL to fetch authentication token", example = "https://auth.example.com/token")
    val authTokenUrl: String,

    @Schema(description = "Whether authentication is required", example = "true", defaultValue = "false")
    val requiresAuth: Boolean = false,

    @Schema(description = "OAuth audience (optional)", example = "https://api.example.com")
    val audience: String? = null
)

@Schema(description = "Retry configuration for failed requests")
data class RetryConfig(
    @field:Min(1, message = "Max attempts must be at least 1")
    @field:Max(10, message = "Max attempts cannot exceed 10")
    @Schema(
        description = "Maximum number of retry attempts",
        example = "3",
        minimum = "1",
        maximum = "10",
        defaultValue = "3"
    )
    val maxAttempts: Int = 3,

    @field:Min(100, message = "Backoff delay must be at least 100ms")
    @Schema(
        description = "Backoff delay between retries in milliseconds",
        example = "1000",
        minimum = "100",
        defaultValue = "1000"
    )
    val backoffDelayMs: Long = 1000,

    @Schema(
        description = "HTTP status codes that should trigger a retry",
        example = "[500, 502, 503, 504]",
        defaultValue = "[500, 502, 503, 504]"
    )
    val retryOnStatusCodes: Set<Int> = setOf(500, 502, 503, 504)
)

@Schema(description = "HTTP request execution result")
data class ExecutionResponse(
    @Schema(description = "Whether the request was successful", example = "true")
    val success: Boolean,

    @Schema(description = "HTTP status code returned", example = "200")
    val statusCode: Int,

    @Schema(description = "Response headers", example = """{"Content-Type": ["application/json"]}""")
    val headers: Map<String, List<String>>,

    @Schema(description = "Request body that was sent", example = """{"name":"John"}""")
    val requestBody: String?,

    @Schema(description = "Response body received", example = """{"id":123,"name":"John"}""")
    val responseBody: String?,

    @Schema(description = "Execution time in milliseconds", example = "245")
    val executionTimeMs: Long,

    @Schema(description = "Error message if request failed", example = "Connection timeout")
    val error: String? = null,

    @Schema(description = "Number of retry attempts made", example = "0")
    val retryAttempts: Int = 0
)
