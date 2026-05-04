package com.nayak.app.feature.service

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.bulk.app.CellData
import com.nayak.app.bulk.app.ExcelData
import com.nayak.app.bulk.app.ExcelRowData
import com.nayak.app.bulk.app.JsonReconstructor
import com.nayak.app.bulk.config.BulkExcelProperties
import com.nayak.app.bulk.config.HeaderMode
import com.nayak.app.common.errors.DomainError
import com.nayak.app.feature.api.CucumberExcelRequest
import com.nayak.app.feature.api.TestFileType
import com.nayak.app.feature.api.TestGenerationResult
import com.nayak.app.project.app.ProjectService
import com.nayak.app.project.model.Project
import com.nayak.app.project.model.ProjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@Service
class FeatureService(
    private val projectService: ProjectService,
    private val objectMapper: ObjectMapper,
    private val excelProps: BulkExcelProperties
) {
    private val logger = LoggerFactory.getLogger(FeatureService::class.java)
    private val jsonReconstructor = JsonReconstructor(objectMapper, excelProps)
//    suspend fun generateGatlingTest(
//        request: GatlingTestRequest,
//        ownerId: String
//    ): Either<DomainError, TestGenerationResult> {
//        return projectService.findProjectById(request.projectId).fold(
//            ifLeft = { it.left() },
//            ifRight = { project ->
//                val content = buildGatlingTest(project, request)
//                TestGenerationResult(
//                    fileName = "${request.testName.replace(" ", "")}Simulation.scala",
//                    content = content,
//                    fileType = TestFileType.GATLING_SCALA
//                ).right()
//            }
//        )
//    }

//    suspend fun generateCucumberFeature(
//        request: CucumberFeatureRequest,
//        ownerId: String
//    ): Either<DomainError, TestGenerationResult> {
//        return projectService.findProjectById(request.projectId).fold(
//            ifLeft = { it.left() },
//            ifRight = { project ->
//                val content = buildCucumberFeature(project, request)
//                TestGenerationResult(
//                    fileName = "${request.featureName.replace(" ", "_").lowercase()}.feature",
//                    content = content,
//                    fileType = TestFileType.CUCUMBER_FEATURE
//                ).right()
//            }
//        )
//    }

//    private fun buildGatlingTest(
//        project: Project,
//        request: GatlingTestRequest
//    ): String {
//        val className = "${request.testName.replace(" ", "")}Simulation"
//        val baseUrl = request.baseUrl ?: extractBaseUrl(project)
//
//        return """
//package simulations
//
//import io.gatling.core.Predef._
//import io.gatling.http.Predef._
//import scala.concurrent.duration._
//
//class $className extends Simulation {
//
//  // HTTP configuration
//  val httpProtocol = http
//    .baseUrl("$baseUrl")
//    .acceptHeader("application/json")
//    .contentTypeHeader("application/json")
//    .userAgentHeader("Gatling Performance Test")
//
//  // Scenario definition
//  val scn = scenario("${request.testName}")
//    ${buildGatlingScenario(project, request)}
//
//  // Load simulation
//  setUp(
//    scn.inject(
//      rampUsers(${request.userCount}) during (${request.rampUpDuration} seconds)
//    )
//  ).protocols(httpProtocol)
//   .maxDuration(${request.testDuration} seconds)
//   .assertions(
//     global.responseTime.max.lt(5000),
//     global.responseTime.mean.lt(1000),
//     global.successfulRequests.percent.gt(95)
//   )
//}
//        """.trimIndent()
//    }

//    private fun buildGatlingScenario(
//        project: Project,
//        request: GatlingTestRequest
//    ): String {
//        val scenarios = mutableListOf<String>()
//
//        when (project.type) {
//            ProjectType.REST -> {
//                val endpoints = project.meta.get("endpoints")
//                if (endpoints?.isArray == true) {
//                    endpoints.forEach { endpoint ->
//                        val path = endpoint.get("path")?.asText() ?: "/"
//                        val method = endpoint.get("method")?.asText()?.lowercase() ?: "get"
//
//                        scenarios.add(
//                            """
//    .exec(
//      http("${method.uppercase()} $path")
//        .$method("$path")
//        ${if (method == "post" || method == "put") buildRequestBody(project.meta) else ""}
//        .check(status.is(200))
//    )"""
//                        )
//                    }
//                } else {
//                    scenarios.add(
//                        """
//    .exec(
//      http("Default Request")
//        .get("/")
//        .check(status.is(200))
//    )"""
//                    )
//                }
//            }
//
//            ProjectType.SOAP -> {
//                val operations = project.meta.get("operations")
//                if (operations?.isArray == true) {
//                    operations.forEach { operation ->
//                        val operationName = operation.asText()
//                        scenarios.add(
//                            """
//    .exec(
//      http("SOAP $operationName")
//        .post("/")
//        .header("Content-Type", "text/xml; charset=utf-8")
//        .header("SOAPAction", "$operationName")
//        ${buildSoapRequestBody(project.meta)}
//        .check(status.is(200))
//    )"""
//                        )
//                    }
//                }
//            }
//        }
//
//        if (request.includeThinkTime) {
//            scenarios.add(
//                """
//    .pause(${request.thinkTimeMin}, ${request.thinkTimeMax})"""
//            )
//        }
//
//        return scenarios.joinToString("\n")
//    }

//    private fun buildRequestBody(meta: JsonNode): String {
//        val requestTemplate = meta.get("requestTemplate")
//        return if (requestTemplate != null) {
//            val bodyContent = requestTemplate.toString().replace("\"", "\\\"")
//            """.body(StringBody("$bodyContent"))"""
//        } else {
//            """.body(StringBody("{\"test\": \"data\"}"))"""
//        }
//    }

//    private fun buildSoapRequestBody(meta: JsonNode): String {
//        val requestTemplate = meta.get("requestTemplate")
//        return if (requestTemplate != null) {
//            val bodyContent = requestTemplate.asText().replace("\"", "\\\"")
//            """.body(StringBody("$bodyContent"))"""
//        } else {
//            """.body(StringBody("<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body></soap:Body></soap:Envelope>"))"""
//        }
//    }

//    private fun buildCucumberFeature(
//        project: Project,
//        request: CucumberFeatureRequest
//    ): String {
//        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
//
//        return """
//Feature: ${request.featureName}
//  As a Test engineer
//  I want to test the ${project.name} API
//  So that I can ensure it works correctly
//
//  # Generated on: $timestamp
//  # Project Type: ${project.type}
//  # Project ID: ${project.id}
//
//Background:
//  Given the API is available
//  And I have valid authentication credentials
//
//${buildCucumberScenarios(project, request)}
//        """.trimIndent()
//    }

//    private fun buildCucumberScenarios(
//        project: Project,
//        request: CucumberFeatureRequest
//    ): String {
//        val scenarios = mutableListOf<String>()
//
//        when (project.type) {
//            ProjectType.REST -> {
//                val endpoints = project.meta.get("endpoints")
//                if (endpoints?.isArray == true) {
//                    endpoints.forEach { endpoint ->
//                        val path = endpoint.get("path")?.asText() ?: "/"
//                        val method = endpoint.get("method")?.asText()?.uppercase() ?: "GET"
//
//                        scenarios.add(
//                            """
//Scenario: Successfully call $method $path
//  When I send a $method request to "$path"
//  ${if (method == "POST" || method == "PUT") "And the request body contains valid data" else ""}
//  Then the response status should be 200
//  ${if (request.includeValidation) "And the response should contain expected data" else ""}
//"""
//                        )
//
//                        if (request.includeErrorScenarios) {
//                            scenarios.add(
//                                """
//Scenario: Handle invalid request to $method $path
//  When I send a $method request to "$path" with invalid data
//  Then the response status should be 400
//  And the response should contain error details
//"""
//                            )
//                        }
//                    }
//                }
//            }
//
//            ProjectType.SOAP -> {
//                val operations = project.meta.get("operations")
//                if (operations?.isArray == true) {
//                    operations.forEach { operation ->
//                        val operationName = operation.asText()
//
//                        scenarios.add(
//                            """
//Scenario: Successfully call SOAP operation $operationName
//  When I send a SOAP request for operation "$operationName"
//  And the SOAP envelope contains valid data
//  Then the response status should be 200
//  And the SOAP response should be valid
//  ${if (request.includeValidation) "And the response should contain expected data" else ""}
//"""
//                        )
//
//                        if (request.includeErrorScenarios) {
//                            scenarios.add(
//                                """
//Scenario: Handle SOAP fault for operation $operationName
//  When I send a SOAP request for operation "$operationName" with invalid data
//  Then the response should contain a SOAP fault
//  And the fault should have appropriate error details
//"""
//                            )
//                        }
//                    }
//                }
//            }
//        }
//
//        return scenarios.joinToString("\n")
//    }

    private fun extractBaseUrl(project: Project): String {
        return when (project.type) {
            ProjectType.REST -> project.meta.get("baseUrl")?.asText() ?: "http://localhost:8080"
            ProjectType.SOAP -> project.meta.get("wsdlUrl")?.asText()?.substringBefore("?wsdl")
                ?: "http://localhost:8080"
        }
    }

    // ==================== Excel-based Cucumber Feature Generation ====================

    /**
     * Generates a Cucumber feature file from an Excel file containing test data.
     * Similar to BulkExecutionService but generates .feature file instead of executing requests.
     */
    suspend fun generateCucumberFromExcel(
        request: CucumberExcelRequest,
        excelFile: InputStream,
        ownerId: String
    ): Either<DomainError, TestGenerationResult> = either {
        val project = projectService.findProjectById(request.projectId).bind()
        ensureNotNull(project) { DomainError.NotFound("Project not found with id ${request.projectId}") }

        val excelData = withContext(Dispatchers.IO) {
            parseExcelFileWithColors(excelFile, request.respectCellColors)
        }.bind()

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = "${project.name.replace(" ", "_")}-$timestamp.feature"

        val featureContent = buildCucumberFeatureFromExcel(project, excelData, request)

        TestGenerationResult(
            fileName = fileName,
            content = featureContent,
            fileType = TestFileType.CUCUMBER_FEATURE
        )
    }

    /**
     * Parses Excel file with color detection for cell exclusions.
     * Reuses logic from BulkExecutionService.
     */
    private fun parseExcelFileWithColors(
        inputStream: InputStream,
        respectColors: Boolean
    ): Either<DomainError, ExcelData> {
        return try {
            val workbook = WorkbookFactory.create(inputStream)
            val sheet = workbook.getSheetAt(0)

            if (sheet.physicalNumberOfRows < 2) {
                workbook.close()
                return DomainError.Validation("Excel file must have at least a header row and one data row").left()
            }

            val formatter = DataFormatter(Locale.getDefault())
            val evaluator = workbook.creationHelper.createFormulaEvaluator()
            val isoDateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

            val headerRow = sheet.getRow(0)
            val headers = (0 until headerRow.physicalNumberOfCells).map {
                headerRow.getCell(it)?.stringCellValue ?: "Column$it"
            }

            val validRows = mutableListOf<ExcelRowData>()
            val skippedRows = mutableListOf<Int>()

            for (rowIndex in 1 until sheet.physicalNumberOfRows) {
                val row = sheet.getRow(rowIndex) ?: continue

                val skipColumnIndex = headers.indexOfFirst { it.equals("Skip Case(Y/N)", ignoreCase = true) }
                if (skipColumnIndex >= 0) {
                    val skipCell = row.getCell(skipColumnIndex)
                    val skipValue = skipCell?.let { formatter.formatCellValue(it, evaluator) }?.trim()?.uppercase()
                    if (skipValue == "Y" || skipValue == "YES") {
                        skippedRows.add(rowIndex)
                        continue
                    }
                }

                val rowData = mutableMapOf<String, CellData>()

                headers.forEachIndexed { colIndex, header ->
                    val cell = row.getCell(colIndex)
                    if (cell == null) {
                        rowData[header] = CellData("", isExcluded = false, typeHint = "BLANK")
                        return@forEachIndexed
                    }

                    val typeHint = when {
                        cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell) -> "DATE"
                        else -> cell.cellType.name
                    }

                    val cellValue = when {
                        cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell) ->
                            isoDateTime.format(cell.dateCellValue)

                        else -> formatter.formatCellValue(cell, evaluator).trim()
                    }

                    val isExcluded = if (respectColors) isCellColoredForExclusion(cell) else false

                    rowData[header] = CellData(
                        value = cellValue,
                        isExcluded = isExcluded,
                        typeHint = typeHint
                    )
                }

                validRows.add(ExcelRowData(rowIndex, rowData))
            }

            workbook.close()
            ExcelData(headers, validRows, skippedRows).right()
        } catch (e: Exception) {
            logger.error("Excel parsing failed", e)
            DomainError.Validation("Excel parsing failed: ${e.message}").left()
        }
    }

    private fun isCellColoredForExclusion(cell: Cell): Boolean = try {
        val cellStyle = cell.cellStyle
        val fillForegroundColor = cellStyle.fillForegroundColor
        // Red (10) or Yellow (13) backgrounds indicate exclusion
        fillForegroundColor == 10.toShort() || fillForegroundColor == 13.toShort()
    } catch (e: Exception) {
        false
    }

    /**
     * Builds Cucumber feature content from Excel data.
     */
    private fun buildCucumberFeatureFromExcel(
        project: Project,
        excelData: ExcelData,
        request: CucumberExcelRequest
    ): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val baseUrl = extractBaseUrl(project)
        val targetUrl = project.meta.path("targetUrl").asText(baseUrl)
        val httpMethod = project.meta.get("method")?.asText() ?: "POST"
        val requiresAuth = project.meta.path("requiresAuth").asBoolean(false)

        val sb = StringBuilder()

        // Feature header
        sb.appendLine("Feature: ${request.featureName}")
        sb.appendLine("  As a Test engineer")
        sb.appendLine("  I want to test the ${project.name} API")
        sb.appendLine("  So that I can ensure it works correctly")
        sb.appendLine()
        sb.appendLine("  # Generated on: $timestamp")
        sb.appendLine("  # Project Type: ${project.type}")
        sb.appendLine("  # Project ID: ${project.id}")
        sb.appendLine("  # Total Scenarios: ${excelData.validRows.size}")
        sb.appendLine("  # Skipped Rows: ${excelData.skippedRows.size}")
        sb.appendLine()

        // Background for auth token (if required)
        if (requiresAuth) {
            sb.appendLine("  Background:")
            sb.appendLine("    Given I have obtained an authentication token")
            sb.appendLine("    And the token is cached for all scenarios")
            sb.appendLine()
        }

        // Generate scenarios for each row
        excelData.validRows.forEachIndexed { index, rowData ->
            val testCaseId = rowData.data["Test Case ID"]?.value?.takeIf { it.isNotBlank() } ?: "TC_${index + 1}"
            val description = rowData.data["Description"]?.value?.takeIf { it.isNotBlank() } ?: "Test case $testCaseId"

            sb.appendLine("  @row_${rowData.originalRowIndex} @$testCaseId")
            sb.appendLine("  Scenario: $description")
            sb.appendLine()

            // Build request body JSON (excluding colored cells)
            val requestJson = buildRequestJsonForScenario(project, rowData.data)
            val requestJsonFormatted = formatJsonForGherkin(requestJson)

            // When step - make the request
            sb.appendLine("    When I send a $httpMethod request to \"$targetUrl\"")
            if (requestJson != null && (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH")) {
                sb.appendLine("    And the request body is:")
                sb.appendLine("      \"\"\"")
                requestJsonFormatted?.lines()?.forEach { line ->
                    sb.appendLine("      $line")
                }
                sb.appendLine("      \"\"\"")
            }
            sb.appendLine()

            // Then step - validate response
            sb.appendLine("    Then the response status should be 200")

            // Build expected response validations (only non-colored EXPECTED_ cells)
            val expectedValidations = buildExpectedValidations(rowData.data, excelData.headers)
            if (expectedValidations.isNotEmpty()) {
                sb.appendLine("    And the response should match:")
                expectedValidations.forEach { (path, value) ->
                    sb.appendLine("      | $path | $value |")
                }
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Builds request JSON from row data, excluding colored cells.
     */
    private fun buildRequestJsonForScenario(
        project: Project,
        rowData: Map<String, CellData>
    ): JsonNode? {
        val template = project.requestTemplate ?: return null

        // Filter to non-excluded cells only
        val filteredRowData = rowData.filterValues { !it.isExcluded }

        return jsonReconstructor.reconstructWithExclusions(template, filteredRowData)
            .fold(
                ifLeft = { error ->
                    logger.warn("JSON reconstruction failed: ${error.message}")
                    null
                },
                ifRight = { it }
            )
    }

    /**
     * Formats JSON for Gherkin doc string.
     */
    private fun formatJsonForGherkin(json: JsonNode?): String? {
        if (json == null) return null
        return try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json)
        } catch (e: Exception) {
            json.toString()
        }
    }

    /**
     * Builds expected validations from EXPECTED_ columns, excluding colored cells.
     * Returns list of (jsonPath, expectedValue) pairs.
     */
    private fun buildExpectedValidations(
        rowData: Map<String, CellData>,
        headers: List<String>
    ): List<Pair<String, String>> {
        val expectedPrefix = "EXPECTED_"
        val validations = mutableListOf<Pair<String, String>>()

        headers.filter { it.startsWith(expectedPrefix) }.forEach { header ->
            val cellData = rowData[header]
            // Only include non-excluded cells with non-empty values
            if (cellData != null && !cellData.isExcluded && cellData.value.isNotBlank()) {
                // Convert header to JSON path (remove EXPECTED_ prefix)
                val jsonPath = headerToJsonPath(header.removePrefix(expectedPrefix))
                validations.add(jsonPath to cellData.value)
            }
        }

        return validations
    }

    /**
     * Converts Excel header name to JSON path notation.
     * Handles both DOT notation and SHORT notation headers.
     */
    private fun headerToJsonPath(header: String): String {
        return if (excelProps.headers.mode == HeaderMode.DOT) {
            // Already in dot notation, just prefix with $
            "$.$header"
        } else {
            // SHORT mode - convert underscores to dots for nested paths
            "$.${header.replace("_", ".")}"
        }
    }
}