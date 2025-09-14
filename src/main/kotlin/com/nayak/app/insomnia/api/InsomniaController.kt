package com.nayak.app.insomnia.api

import com.fasterxml.jackson.databind.JsonNode
import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.insomnia.service.InsomniaService
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
class InsomniaController(private val insomniaService: InsomniaService) {

    @Operation(summary = "Execute dynamic request to downstream service")
    @PostMapping
    suspend fun makeRequest(@Valid @RequestBody request: InsomniaRequest): ResponseEntity<ApiResponse<Any>> {

        return insomniaService.executeRequest(request).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { response -> ResponseEntity.ok(ApiResponse.success(response, "Request executed successfully")) }
        )
    }
}


data class InsomniaRequest(
    @field:NotBlank(message = "Target URL cannot be blank")
    val targetUrl: String,

    val requestBody: JsonNode? = null,
    @field:NotNull(message = "HTTP method cannot be null")
    val httpMethod: HttpMethod,

    val authConfig: AuthConfig? = null,

    val headers: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),

    val requestType: ProjectType = ProjectType.SOAP,
    // Retry configuration
    val retryConfig: RetryConfig? = null,

    val timeoutSeconds: Int = 30,
)

data class RetryConfig(
    val maxRetries: Int = 2,
    val backoffDelayMs: Long = 1000,
    val retryOnStatusCodes: Set<Int> = setOf(500, 502, 503, 504, 429)
)

data class InsomniaResponse(
    val success: Boolean,
    val statusCode: Int,
    val executionTimeMs: Long,
    val error: String? = null,
    val retryAttempts: Int = 0,
    val responseBody: String?,
    val headers: Map<String, List<String>>,
)

data class AuthConfig(
    val required: Boolean = false,
    val tokenUrl: String? = null,
    val audience: String? = null,
    val additionalParams: Map<String, String> = emptyMap()
)