package com.nayak.app.insomnia.api

import com.fasterxml.jackson.databind.JsonNode
import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.insomnia.service.ExecutionService
import com.nayak.app.project.model.ProjectType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/insomnia")
@Tag(name = "Insomnia", description = "Insomnia Interface API")
class InsomniaController(private val executionService: ExecutionService) {

    @Operation(summary = "Execute dynamic request to downstream service")
    @PostMapping
    suspend fun makeRequest(@Valid @RequestBody request: ExecutionRequest): ResponseEntity<ApiResponse<Any>> {

        return executionService.executeRequest(request).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { response -> ResponseEntity.ok(ApiResponse.success(response, "Request executed successfully")) }
        )
    }
}

data class ExecutionRequest(
    @field:NotBlank(message = "Target URL cannot be blank")
    val targetUrl: String,

    @field:NotNull(message = "HTTP method cannot be null")
    val httpMethod: HttpMethod,

    @field:NotNull(message = "Request type cannot be null")
    val requestType: ProjectType,

    val headers: Map<String, String> = emptyMap(),
    val requestBody: JsonNode? = null,
    val queryParams: Map<String, String> = emptyMap(),

    // Authentication configuration
    val authConfig: AuthConfig? = null,

    // Timeout configuration
    val timeoutSeconds: Int = 30,

    // Retry configuration
    val retryConfig: RetryConfig? = null
)

data class AuthConfig(
    val required: Boolean = false,
    val tokenUrl: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val audience: String? = null,
    val scope: String? = null,
    val grantType: String = "client_credentials",
    val additionalParams: Map<String, String> = emptyMap(),
    val cacheToken: Boolean = true // Whether to cache the token
)

data class RetryConfig(
    val maxAttempts: Int = 3,
    val backoffDelayMs: Long = 1000,
    val retryOnStatusCodes: Set<Int> = setOf(500, 502, 503, 504)
)


data class ExecutionResponse(
    val success: Boolean,
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val responseBody: String?,
    val executionTimeMs: Long,
    val error: String? = null,
    val retryAttempts: Int = 0
)