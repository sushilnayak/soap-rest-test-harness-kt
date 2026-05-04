package com.nayak.app.feature.api

import com.nayak.app.common.http.ApiResponse
import com.nayak.app.common.http.toResponse
import com.nayak.app.feature.service.FeatureService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import java.util.*
import io.swagger.v3.oas.annotations.responses.ApiResponse as SwaggerApiResponse

@RestController
@RequestMapping("/api/feature")
@Tag(name = "Test Generation", description = "Generate test files (Gatling, Cucumber) from project definitions")
@SecurityRequirement(name = "bearer-jwt")
class FeatureGenerationController(private val featureService: FeatureService) {
//
//    @PostMapping("/gatling")
//    @Operation(
//        summary = "Generate Gatling performance test",
//        description = "Generate a Gatling Scala performance test script from a project definition with configurable load parameters"
//    )
//    @SwaggerRequestBody(
//        description = "Gatling test generation configuration",
//        required = true,
//        content = [Content(
//            mediaType = MediaType.APPLICATION_JSON_VALUE,
//            schema = Schema(implementation = GatlingTestRequest::class),
//            examples = [ExampleObject(
//                value = """{
//                    "projectId": "550e8400-e29b-41d4-a716-446655440000",
//                    "testName": "UserApiLoadTest",
//                    "baseUrl": "https://api.example.com",
//                    "userCount": 50,
//                    "rampUpDuration": 60,
//                    "testDuration": 300,
//                    "includeThinkTime": true,
//                    "thinkTimeMin": 1,
//                    "thinkTimeMax": 5
//                }"""
//            )]
//        )]
//    )
//    @ApiResponses(
//        value = [
//            SwaggerApiResponse(
//                responseCode = "200",
//                description = "Gatling test generated successfully",
//                content = [Content(
//                    mediaType = MediaType.APPLICATION_JSON_VALUE,
//                    schema = Schema(implementation = TestGenerationResult::class),
//                    examples = [ExampleObject(
//                        value = """{
//                            "success": true,
//                            "data": {
//                                "fileName": "UserApiLoadTest.scala",
//                                "content": "package simulations\n\nimport io.gatling.core.Predef._\n...",
//                                "fileType": "GATLING_SCALA"
//                            },
//                            "message": "Gatling test generated successfully",
//                            "error": null,
//                            "timestamp": "2024-01-15T10:30:00Z"
//                        }"""
//                    )]
//                )]
//            ),
//            SwaggerApiResponse(
//                responseCode = "400",
//                description = "Bad request - validation failed"
//            ),
//            SwaggerApiResponse(
//                responseCode = "401",
//                description = "Unauthorized - invalid or missing token"
//            ),
//            SwaggerApiResponse(
//                responseCode = "404",
//                description = "Project not found"
//            )
//        ]
//    )
//    suspend fun generateGatlingTest(
//        @Valid @RequestBody request: GatlingTestRequest,
//        @AuthenticationPrincipal userId: String
//    ): ResponseEntity<ApiResponse<TestGenerationResult>> =
//        featureService.generateGatlingTest(request, userId).toResponse("Gatling test generated successfully")
//
//    @PostMapping("/cucumber")
//    @Operation(
//        summary = "Generate Cucumber feature file",
//        description = "Generate a Cucumber BDD feature file from a project definition with optional validation and error scenarios"
//    )
//    @SwaggerRequestBody(
//        description = "Cucumber feature generation configuration",
//        required = true,
//        content = [Content(
//            mediaType = MediaType.APPLICATION_JSON_VALUE,
//            schema = Schema(implementation = CucumberFeatureRequest::class),
//            examples = [ExampleObject(
//                value = """{
//                    "projectId": "550e8400-e29b-41d4-a716-446655440000",
//                    "featureName": "User Management API",
//                    "includeValidation": true,
//                    "includeErrorScenarios": true
//                }"""
//            )]
//        )]
//    )
//    @ApiResponses(
//        value = [
//            SwaggerApiResponse(
//                responseCode = "200",
//                description = "Cucumber feature generated successfully",
//                content = [Content(
//                    mediaType = MediaType.APPLICATION_JSON_VALUE,
//                    schema = Schema(implementation = TestGenerationResult::class),
//                    examples = [ExampleObject(
//                        value = """{
//                            "success": true,
//                            "data": {
//                                "fileName": "user_management_api.feature",
//                                "content": "Feature: User Management API\n\n  Scenario: Create a new user\n...",
//                                "fileType": "CUCUMBER_FEATURE"
//                            },
//                            "message": "Cucumber feature generated successfully",
//                            "error": null,
//                            "timestamp": "2024-01-15T10:30:00Z"
//                        }"""
//                    )]
//                )]
//            ),
//            SwaggerApiResponse(
//                responseCode = "400",
//                description = "Bad request - validation failed"
//            ),
//            SwaggerApiResponse(
//                responseCode = "401",
//                description = "Unauthorized - invalid or missing token"
//            ),
//            SwaggerApiResponse(
//                responseCode = "404",
//                description = "Project not found"
//            )
//        ]
//    )
//    suspend fun generateCucumberFeature(
//        @Valid @RequestBody request: CucumberFeatureRequest,
//        @AuthenticationPrincipal userId: String
//    ): ResponseEntity<ApiResponse<TestGenerationResult>> =
//        featureService.generateCucumberFeature(request, userId).toResponse("Cucumber feature generated successfully")

    @PostMapping("/cucumber/from-excel", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Generate Cucumber feature from Excel",
        description = """
            Upload an Excel file with test data and generate a Cucumber BDD feature file.
            Similar to bulk execution but generates a .feature file instead of executing requests.
            
            Excel file should contain:
            - Header row with column names matching request template fields
            - Data rows with test case parameters
            - Optional 'Test Case ID' and 'Description' columns for scenario naming
            - Optional 'Skip Case(Y/N)' column to skip specific rows
            - EXPECTED_ prefixed columns for response validation assertions
            - Cell colors can be used to exclude specific values (if respectCellColors is true)
            
            Generated feature file includes:
            - Background step for auth token (if project requires authentication)
            - Individual scenarios for each Excel row
            - Request body built from non-colored cells
            - Response validation only for non-colored EXPECTED_ cells
        """
    )
    @ApiResponses(
        value = [
            SwaggerApiResponse(
                responseCode = "200",
                description = "Cucumber feature generated successfully",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = TestGenerationResult::class),
                    examples = [ExampleObject(
                        value = """{
                            "success": true,
                            "data": {
                                "fileName": "User_API_Tests-20240115_103000.feature",
                                "content": "Feature: User API Tests\n  As a Test engineer\n  ...",
                                "fileType": "CUCUMBER_FEATURE"
                            },
                            "message": "Cucumber feature generated from Excel",
                            "error": null,
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "400",
                description = "Bad request - invalid file format or validation failed",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    examples = [ExampleObject(
                        value = """{
                            "success": false,
                            "data": null,
                            "message": null,
                            "error": "Please provide an XLSX/XLS file",
                            "timestamp": "2024-01-15T10:30:00Z"
                        }"""
                    )]
                )]
            ),
            SwaggerApiResponse(
                responseCode = "401",
                description = "Unauthorized - invalid or missing token"
            ),
            SwaggerApiResponse(
                responseCode = "404",
                description = "Project not found"
            ),
            SwaggerApiResponse(
                responseCode = "500",
                description = "Internal server error - Excel parsing or generation failed"
            )
        ]
    )
    suspend fun generateCucumberFromExcel(
        @Parameter(
            description = "Cucumber feature generation configuration",
            required = true,
            schema = Schema(implementation = CucumberExcelRequest::class)
        )
        @RequestPart("request") request: CucumberExcelRequest,

        @Parameter(
            description = "Excel file (.xlsx or .xls) containing test data",
            required = true,
            content = [Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")]
        )
        @RequestPart("file") file: org.springframework.http.codec.multipart.FilePart,

        @AuthenticationPrincipal userId: String
    ): ResponseEntity<ApiResponse<TestGenerationResult>> {
        val name = file.filename().lowercase()
        if (!(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Please provide an XLSX/XLS file"))
        }

        val tempFile = kotlin.io.path.createTempFile("cucumber-", "-" + file.filename()).toFile()
        file.transferTo(tempFile).awaitSingleOrNull()
        return tempFile.inputStream().use { inputStream ->
            featureService.generateCucumberFromExcel(request, inputStream, userId)
                .toResponse("Cucumber feature generated from Excel")
        }
    }
}

//@Schema(description = "Gatling performance test generation request")
//data class GatlingTestRequest(
//    @field:NotNull(message = "Project ID cannot be null")
//    @Schema(
//        description = "ID of the project to generate test from",
//        example = "550e8400-e29b-41d4-a716-446655440000",
//        required = true
//    )
//    val projectId: UUID,
//
//    @field:NotBlank(message = "Test name cannot be blank")
//    @Schema(
//        description = "Name of the Gatling test simulation",
//        example = "UserApiLoadTest",
//        required = true
//    )
//    val testName: String,
//
//    @Schema(
//        description = "Base URL for the test (overrides project URL if provided)",
//        example = "https://api.example.com"
//    )
//    val baseUrl: String? = null,
//
//    @field:Min(1, message = "User count must be at least 1")
//    @field:Max(10000, message = "User count cannot exceed 10000")
//    @Schema(
//        description = "Number of concurrent virtual users",
//        example = "50",
//        minimum = "1",
//        maximum = "10000",
//        defaultValue = "10"
//    )
//    val userCount: Int = 10,
//
//    @field:Min(1, message = "Ramp up duration must be at least 1 second")
//    @field:Max(3600, message = "Ramp up duration cannot exceed 3600 seconds")
//    @Schema(
//        description = "Ramp up duration in seconds",
//        example = "60",
//        minimum = "1",
//        maximum = "3600",
//        defaultValue = "30"
//    )
//    val rampUpDuration: Int = 30,
//
//    @field:Min(1, message = "Test duration must be at least 1 second")
//    @field:Max(86400, message = "Test duration cannot exceed 86400 seconds (24 hours)")
//    @Schema(
//        description = "Total test duration in seconds",
//        example = "300",
//        minimum = "1",
//        maximum = "86400",
//        defaultValue = "300"
//    )
//    val testDuration: Int = 300,
//
//    @Schema(
//        description = "Include think time between requests",
//        example = "true",
//        defaultValue = "true"
//    )
//    val includeThinkTime: Boolean = true,
//
//    @field:Min(0, message = "Think time min cannot be negative")
//    @Schema(
//        description = "Minimum think time in seconds",
//        example = "1",
//        minimum = "0",
//        defaultValue = "1"
//    )
//    val thinkTimeMin: Int = 1,
//
//    @field:Min(0, message = "Think time max cannot be negative")
//    @Schema(
//        description = "Maximum think time in seconds",
//        example = "5",
//        minimum = "0",
//        defaultValue = "3"
//    )
//    val thinkTimeMax: Int = 3
//)
//
//@Schema(description = "Cucumber feature file generation request")
//data class CucumberFeatureRequest(
//    @field:NotNull(message = "Project ID cannot be null")
//    @Schema(
//        description = "ID of the project to generate feature from",
//        example = "550e8400-e29b-41d4-a716-446655440000",
//        required = true
//    )
//    val projectId: UUID,
//
//    @field:NotBlank(message = "Feature name cannot be blank")
//    @Schema(
//        description = "Name of the Cucumber feature",
//        example = "User Management API",
//        required = true
//    )
//    val featureName: String,
//
//    @Schema(
//        description = "Include validation scenarios",
//        example = "true",
//        defaultValue = "true"
//    )
//    val includeValidation: Boolean = true,
//
//    @Schema(
//        description = "Include error handling scenarios",
//        example = "true",
//        defaultValue = "true"
//    )
//    val includeErrorScenarios: Boolean = true
//)

@Schema(description = "Generated test file result")
data class TestGenerationResult(
    @Schema(description = "Generated file name", example = "UserApiLoadTest.scala")
    val fileName: String,

    @Schema(description = "Generated file content")
    val content: String,

    @Schema(description = "Type of generated test file", example = "GATLING_SCALA")
    val fileType: TestFileType
)

@Schema(description = "Type of generated test file")
enum class TestFileType {
    @Schema(description = "Gatling Scala simulation file")
    GATLING_SCALA,

    @Schema(description = "Cucumber Gherkin feature file")
    CUCUMBER_FEATURE,

    @Schema(description = "Postman collection JSON")
    POSTMAN_COLLECTION
}

@Schema(description = "Cucumber feature generation from Excel request")
data class CucumberExcelRequest(
    @field:NotNull(message = "Project ID cannot be null")
    @Schema(
        description = "ID of the project to generate feature from",
        example = "550e8400-e29b-41d4-a716-446655440000",
        required = true
    )
    val projectId: UUID,

    @field:NotBlank(message = "Feature name cannot be blank")
    @Schema(
        description = "Name of the Cucumber feature",
        example = "User Management API Tests",
        required = true
    )
    val featureName: String,

    @Schema(
        description = "Use cell background colors to exclude values from request body and response validation",
        example = "true",
        defaultValue = "true"
    )
    val respectCellColors: Boolean = true
)
