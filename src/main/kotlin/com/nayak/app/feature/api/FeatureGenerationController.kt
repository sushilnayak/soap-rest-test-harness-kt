package com.nayak.app.feature.api

import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.feature.service.FeatureService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping("/api/feature")
@Tag(name = "Feature Generation", description = "Generate feature files for projects")
class FeatureGenerationController(private val featureService: FeatureService) {


    @PostMapping("/gatling")
    @Operation(summary = "Generate Gatling performance test")
    suspend fun generateGatlingTest(
        @Valid @RequestBody request: GatlingTestRequest,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<Any>> {
        return featureService.generateGatlingTest(request, userId).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { result ->
                ResponseEntity.ok(ApiResponse.success(result, "Gatling test generated successfully"))
            }
        )
    }

    @PostMapping("/cucumber")
    @Operation(summary = "Generate Cucumber feature file")
    suspend fun generateCucumberFeature(
        @Valid @RequestBody request: CucumberFeatureRequest,
        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<Any>> {
        return featureService.generateCucumberFeature(request, userId).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus())
                    .body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { result ->
                ResponseEntity.ok(ApiResponse.success(result, "Cucumber feature generated successfully"))
            }
        )
    }
}


data class GatlingTestRequest(
    val projectId: UUID,
    val testName: String,
    val baseUrl: String? = null,
    val userCount: Int = 10,
    val rampUpDuration: Int = 30,
    val testDuration: Int = 300,
    val includeThinkTime: Boolean = true,
    val thinkTimeMin: Int = 1,
    val thinkTimeMax: Int = 3
)

data class CucumberFeatureRequest(
    val projectId: UUID,
    val featureName: String,
    val includeValidation: Boolean = true,
    val includeErrorScenarios: Boolean = true,
)

data class TestGenerationResult(
    val fileName: String,
    val content: String,
    val fileType: TestFileType
)

enum class TestFileType{
    GATLING_SCALA,
    CUCUMBER_FEATURE,
    POSTMAN_COLLECTION
}