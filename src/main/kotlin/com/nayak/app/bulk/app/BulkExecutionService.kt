package com.nayak.app.bulk.app

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.bulk.domain.*
import com.nayak.app.bulk.repo.BulkExecutionRepository
import com.nayak.app.common.errors.DomainError
import com.nayak.app.insomnia.api.AuthConfig
import com.nayak.app.insomnia.api.ExecutionRequest
import com.nayak.app.insomnia.service.ExecutionService
import com.nayak.app.jobs.app.JobExecutionService
import com.nayak.app.jobs.domain.*
import com.nayak.app.project.app.ProjectService
import com.nayak.app.project.model.Project
import com.nayak.app.project.model.ProjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.*

@Service
class BulkExecutionService(
    private val bulkExecutionRepository: BulkExecutionRepository,
    private val jobExecutionService: JobExecutionService,
    private val projectService: ProjectService,
    private val executionService: ExecutionService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(BulkExecutionService::class.java)

    private val tokenCache = mutableMapOf<String, CachedToken>()

    suspend fun processBulkExecution(
        request: BulkExecutionRequest,
        excelFile: InputStream,
        ownerId: String
    ): Either<DomainError, BulkExecutionResponseDto> =
        either {
            val project = projectService.findProjectById(request.projectId).bind()

            ensureNotNull(project) { DomainError.NotFound("Project id not found with id $request.projectId") }

            val excelData = withContext(Dispatchers.IO) {
                parseExcelFileWithColors(excelFile, request.respectCellColors)
            }.bind()

            // 3) Create + persist BulkExecution
            val bulkExecution = BulkExecution(
                projectId = project.id!!,
                ownerId = ownerId,
                projectName = project.name,
                status = BulkExecutionStatus.PENDING,
                totalRows = excelData.validRows.size
            )

            val savedExecution = bulkExecutionRepository.save(bulkExecution)

            val executionId = ensureNotNull(savedExecution.id) {
                DomainError.Database("BulkExecution persisted without id")
            }

            val jobPayload = BulkExecutionJobPayload(
                bulkExecutionId = executionId,
                projectId = project.id,
                request = request,
                excelData = ExcelJobData(
                    headers = excelData.headers,
                    validRows = excelData.validRows.map { row ->
                        ExcelRowJobData(
                            originalRowIndex = row.originalRowIndex,
                            data = row.data.mapValues { CellJobData(it.value.value, it.value.isExcluded) }
                        )
                    },
                    skippedRows = excelData.skippedRows
                )
            )

            // 6) Create persistent job record
            val job = jobExecutionService.createJob(
                executionId = executionId.toString(),
                jobType = JobType.BULK_EXECUTION,
                ownerId = ownerId,
                payload = jobPayload,
                maxRetries = 2
            ).bind()

            if (request.executeImmediately) {
                jobExecutionService.executeJobAsync(job) { jobExecution ->
                    executeBulkJob(jobExecution)
                }
            }

            BulkExecutionResponseDto(jobPayload.bulkExecutionId, jobPayload.projectId)
        }.mapLeft { e ->
            logger.error("Bulk execution setup failed: ${e.message}", e)
            e
        }

    private suspend fun executeBulkJob(job: JobExecution) {
        val executionId = job.executionId

        try {
            // Set up structured logging context
            MDC.put("executionId", executionId)
            MDC.put("jobType", job.jobType.name)
            MDC.put("ownerId", job.ownerId)

            // Parse job payload
            val payload = objectMapper.treeToValue(job.jobPayload, BulkExecutionJobPayload::class.java)
            val bulkExecution = bulkExecutionRepository.findById(payload.bulkExecutionId)
                ?: throw RuntimeException("Bulk execution not found: ${payload.bulkExecutionId}")

            val project = projectService.findProjectById(payload.projectId).fold(
                ifLeft = { throw RuntimeException("Project not found: ${payload.projectId}") },
                ifRight = { it }
            )

            // Convert job data back to processing format
            val excelData = ExcelData(
                headers = payload.excelData.headers,
                validRows = payload.excelData.validRows.map { row ->
                    ExcelRowData(
                        originalRowIndex = row.originalRowIndex,
                        data = row.data.mapValues { CellData(it.value.value, it.value.isExcluded) }
                    )
                },
                skippedRows = payload.excelData.skippedRows
            )

            logger.info(
                "Starting bulk execution job: executionId={}, totalRows={}",
                executionId, excelData.validRows.size
            )

            // Process rows with enhanced logging and progress tracking
            processRowsWithJobTracking(bulkExecution, project, excelData, payload.request, executionId)

        } catch (e: Exception) {
            logger.error("Bulk execution job failed: executionId={}", executionId, e)
            throw e
        } finally {
            MDC.clear()
        }
    }

    private suspend fun parseExcelFileWithColors(
        inputStream: InputStream,
        respectColors: Boolean
    ): Either<DomainError, ExcelData> {
        return try {
            val workbook = WorkbookFactory.create(inputStream)
            val sheet = workbook.getSheetAt(0)

            if (sheet.physicalNumberOfRows < 2) {
                return DomainError.Validation("Excel file must have at least a header row and one data row").left()
            }

            // Get headers from first row
            val headerRow = sheet.getRow(0)
            val headers = (0 until headerRow.physicalNumberOfCells).map {
                headerRow.getCell(it)?.stringCellValue ?: "Column$it"
            }

            // Parse data rows with color information
            val validRows = mutableListOf<ExcelRowData>()
            val skippedRows = mutableListOf<Int>()

            for (rowIndex in 1 until sheet.physicalNumberOfRows) {
                val row = sheet.getRow(rowIndex) ?: continue

                // Check if row should be skipped based on "Skip Case(Y/N)" column
                val skipColumnIndex = headers.indexOfFirst { it.equals("Skip Case(Y/N)", ignoreCase = true) }
                if (skipColumnIndex >= 0) {
                    val skipCell = row.getCell(skipColumnIndex)
                    val skipValue = skipCell?.stringCellValue?.trim()?.uppercase()
                    if (skipValue == "Y" || skipValue == "YES") {
                        skippedRows.add(rowIndex)
                        continue
                    }
                }

                val rowData = mutableMapOf<String, CellData>()

                headers.forEachIndexed { colIndex, header ->
                    val cell = row.getCell(colIndex)
                    val cellValue = cell?.toString() ?: ""

                    // Check cell color if respectColors is enabled
                    val isExcluded = if (respectColors && cell != null) {
                        isCellColoredForExclusion(cell)
                    } else false

                    rowData[header] = CellData(cellValue, isExcluded)
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

    private fun isCellColoredForExclusion(cell: Cell): Boolean {
        return try {
            val cellStyle = cell.cellStyle
            val fillForegroundColor = cellStyle.fillForegroundColor

            // Consider cells with red background (index 10) or yellow background (index 13) as excluded
            // You can customize this logic based on your color scheme
            fillForegroundColor == 10.toShort() || fillForegroundColor == 13.toShort()
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun processRowsWithJobTracking(
        execution: BulkExecution,
        project: Project,
        excelData: ExcelData,
        request: BulkExecutionRequest,
        executionId: String
    ) {
        try {
            // Update status to processing
            bulkExecutionRepository.save(execution.copy(status = BulkExecutionStatus.PROCESSING))

            // Get authentication token once if caching is enabled
            var cachedAuthToken: String? = null
            if (request.cacheAuthToken) {
                cachedAuthToken = getCachedAuthToken(project, request)
            }

            val results = mutableListOf<BulkExecutionResult>()
            var successCount = 0
            var failureCount = 0

            // Initialize progress tracking
            jobExecutionService.updateJobProgress(
                executionId, JobProgressInfo(
                    totalItems = excelData.validRows.size,
                    processedItems = 0,
                    successfulItems = 0,
                    failedItems = 0
                )
            )

            // Process each row
            excelData.validRows.forEachIndexed { index, rowData ->
                try {
                    // Set row-specific MDC context
                    MDC.put("rowIndex", rowData.originalRowIndex.toString())
                    MDC.put("currentRow", "${index + 1}/${excelData.validRows.size}")

                    val executionRequest = buildExecutionRequest(
                        project,
                        rowData.data,
                        request,
                        cachedAuthToken
                    )
                    val startTime = System.currentTimeMillis()

                    executionService.executeRequest(executionRequest).fold(
                        ifLeft = { error ->
                            logger.warn(
                                "Row execution failed: rowIndex={}, error={}",
                                rowData.originalRowIndex, error.message
                            )

                            // Log sanitized request for debugging
                            logFailedRequest(executionRequest, error.message)

                            results.add(
                                BulkExecutionResult(
                                    rowIndex = rowData.originalRowIndex,
                                    success = false,
                                    error = error.message,
                                    executionTimeMs = System.currentTimeMillis() - startTime
                                )
                            )
                            failureCount++
                        },
                        ifRight = { response ->
                            if (response.success) {
                                logger.debug(
                                    "Row execution succeeded: rowIndex={}, statusCode={}, duration={}ms",
                                    rowData.originalRowIndex, response.statusCode, response.executionTimeMs
                                )
                            } else {
                                logger.warn(
                                    "Row execution returned error: rowIndex={}, statusCode={}, duration={}ms",
                                    rowData.originalRowIndex, response.statusCode, response.executionTimeMs
                                )

                                // Log sanitized request for debugging
                                logFailedRequest(executionRequest, "HTTP ${response.statusCode}")
                            }

                            results.add(
                                BulkExecutionResult(
                                    rowIndex = rowData.originalRowIndex,
                                    success = response.success,
                                    statusCode = response.statusCode,
                                    responseBody = response.responseBody,
                                    error = if (!response.success) "HTTP ${response.statusCode}" else null,
                                    executionTimeMs = response.executionTimeMs
                                )
                            )
                            if (response.success) successCount++ else failureCount++
                        }
                    )

                    // Update progress periodically
                    if ((index + 1) % 10 == 0) {
                        jobExecutionService.updateJobProgress(
                            executionId, JobProgressInfo(
                                totalItems = excelData.validRows.size,
                                processedItems = index + 1,
                                successfulItems = successCount,
                                failedItems = failureCount,
                                currentItem = "Row ${rowData.originalRowIndex}"
                            )
                        )

                        bulkExecutionRepository.save(
                            execution.copy(
                                processedRows = index + 1,
                                successfulRows = successCount,
                                failedRows = failureCount
                            )
                        )
                    }
                } catch (e: Exception) {
                    logger.error(
                        "Row processing failed: rowIndex={}, index={}",
                        rowData.originalRowIndex, index, e
                    )
                    results.add(
                        BulkExecutionResult(
                            rowIndex = rowData.originalRowIndex,
                            success = false,
                            error = "Processing error: ${e.message}"
                        )
                    )
                    failureCount++
                } finally {
                    // Clear row-specific MDC
                    MDC.remove("rowIndex")
                    MDC.remove("currentRow")
                }
            }

            // Final progress update
            jobExecutionService.updateJobProgress(
                executionId, JobProgressInfo(
                    totalItems = excelData.validRows.size,
                    processedItems = excelData.validRows.size,
                    successfulItems = successCount,
                    failedItems = failureCount
                )
            )

            // Final update
            bulkExecutionRepository.save(
                execution.copy(
                    status = BulkExecutionStatus.COMPLETED,
                    processedRows = excelData.validRows.size,
                    successfulRows = successCount,
                    failedRows = failureCount,
                    results = objectMapper.valueToTree(results)
                )
            )

            logger.info(
                "Bulk execution completed: executionId={}, total={}, success={}, failed={}",
                executionId, excelData.validRows.size, successCount, failureCount
            )

        } catch (e: Exception) {
            logger.error("Bulk execution processing failed: executionId={}", executionId, e)
            bulkExecutionRepository.save(
                execution.copy(
                    status = BulkExecutionStatus.FAILED,
                    errorDetails = e.message
                )
            )
        }
    }


//    private suspend fun processRows(
//        execution: BulkExecution,
//        project: Project,
//        excelData: ExcelData,
//        request: BulkExecutionRequest
//    ) {
//        try {
//            // Update status to processing
//            bulkExecutionRepository.save(execution.copy(status = BulkExecutionStatus.PROCESSING))
//
//            // Get authentication token once if caching is enabled
//            var cachedAuthToken: String? = null
//            if (request.cacheAuthToken) {
//                cachedAuthToken = getCachedAuthToken(project, request)
//            }
//
//            val results = mutableListOf<BulkExecutionResult>()
//            var successCount = 0
//            var failureCount = 0
//
//            // Process each row
//            excelData.validRows.forEachIndexed { index, rowData ->
//                try {
//                    val executionRequest = buildExecutionRequest(
//                        project,
//                        rowData.data,
//                        request,
//                        cachedAuthToken
//                    )
//                    val startTime = System.currentTimeMillis()
//
//                    executionService.executeRequest(executionRequest).fold(
//                        ifLeft = { error ->
//                            results.add(
//                                BulkExecutionResult(
//                                    rowIndex = rowData.originalRowIndex,
//                                    success = false,
//                                    error = error.message,
//                                    executionTimeMs = System.currentTimeMillis() - startTime
//                                )
//                            )
//                            failureCount++
//                        },
//                        ifRight = { response ->
//                            results.add(
//                                BulkExecutionResult(
//                                    rowIndex = rowData.originalRowIndex,
//                                    success = response.success,
//                                    statusCode = response.statusCode,
//                                    responseBody = response.responseBody,
//                                    error = if (!response.success) "HTTP ${response.statusCode}" else null,
//                                    executionTimeMs = response.executionTimeMs
//                                )
//                            )
//                            if (response.success) successCount++ else failureCount++
//                        }
//                    )
//
//                    // Update progress periodically
//                    if ((index + 1) % 10 == 0) {
//                        bulkExecutionRepository.save(
//                            execution.copy(
//                                processedRows = index + 1,
//                                successfulRows = successCount,
//                                failedRows = failureCount
//                            )
//                        )
//                    }
//                } catch (e: Exception) {
//                    logger.error("Row processing failed for index $index", e)
//                    results.add(
//                        BulkExecutionResult(
//                            rowIndex = rowData.originalRowIndex,
//                            success = false,
//                            error = "Processing error: ${e.message}"
//                        )
//                    )
//                    failureCount++
//                }
//            }
//
//            // Final update
//            bulkExecutionRepository.save(
//                execution.copy(
//                    status = BulkExecutionStatus.COMPLETED,
//                    processedRows = excelData.validRows.size,
//                    successfulRows = successCount,
//                    failedRows = failureCount,
//                    results = objectMapper.valueToTree(results)
//                )
//            )
//
//        } catch (e: Exception) {
//            logger.error("Bulk execution processing failed", e)
//            bulkExecutionRepository.save(
//                execution.copy(
//                    status = BulkExecutionStatus.FAILED,
//                    errorDetails = e.message
//                )
//            )
//        }
//    }

    private fun logFailedRequest(request: ExecutionRequest, error: String) {
        try {
            // Create sanitized request for logging (remove sensitive data)
            val sanitizedHeaders = request.headers.mapValues { (key, value) ->
                if (key.lowercase().contains("authorization") ||
                    key.lowercase().contains("token") ||
                    key.lowercase().contains("secret")
                ) {
                    "***REDACTED***"
                } else value
            }

            val sanitizedRequest = mapOf(
                "url" to request.targetUrl,
                "method" to request.httpMethod.name(),
                "headers" to sanitizedHeaders,
                "hasBody" to (request.requestBody != null),
                "bodySize" to (request.requestBody?.toString()?.length ?: 0),
                "error" to error
            )

            logger.error("Failed request details: {}", objectMapper.writeValueAsString(sanitizedRequest))
        } catch (e: Exception) {
            logger.warn("Failed to log request details", e)
        }
    }

    private suspend fun getCachedAuthToken(
        project: Project,
        request: BulkExecutionRequest
    ): String? {
        return try {
            // Extract auth config from project metadata
            val authConfig = extractAuthConfigFromProject(project)
            if (authConfig?.requiresAuth == true) {
                val cacheKey = "${authConfig.authTokenUrl}-${authConfig.audience}"

                // Check if token is cached and not expired
                val cached = tokenCache[cacheKey]
                if (cached != null && !cached.isExpired()) {
                    return cached.token
                }

                // Get new token and cache it
                val newToken = executionService.getAuthToken(authConfig).fold(
                    ifLeft = { null },
                    ifRight = { it }
                )

                newToken?.let {
                    tokenCache[cacheKey] = CachedToken(it, System.currentTimeMillis() + 3300000) // 55 minutes
                }

                newToken
            } else null
        } catch (e: Exception) {
            logger.warn("Failed to get cached auth token", e)
            null
        }
    }

    private fun buildExecutionRequest(
        project: Project,
        rowData: Map<String, CellData>,
        request: BulkExecutionRequest,
        cachedAuthToken: String? = null
    ): ExecutionRequest {
        // Filter out excluded cells and replace placeholders
        val filteredRowData = rowData.filterNot { it.value.isExcluded }
            .mapValues { it.value.value }

        val processedMeta = replaceTemplateVariables(project.meta, filteredRowData)

        // Determine target URL
        val targetUrl = project.meta.path("targetUrl").asText(null)

        // Handle conversion modes
        return when (request.conversionMode) {
            ConversionMode.SOAP_TO_REST -> buildRestFromSoap(targetUrl, processedMeta, filteredRowData, cachedAuthToken)
            ConversionMode.REST_TO_SOAP -> buildSoapFromRest(targetUrl, processedMeta, filteredRowData, cachedAuthToken)
            ConversionMode.NONE -> buildDirectRequest(
                project,
                targetUrl,
                processedMeta,
                filteredRowData,
                cachedAuthToken
            )
        }.let { executionRequest ->
            // Override auth config if we have a cached token
            if (cachedAuthToken != null) {
                executionRequest.copy(authConfig = null) // Don't fetch token again
            } else executionRequest
        }
    }

    private fun replaceTemplateVariables(meta: JsonNode, rowData: Map<String, String>): JsonNode {
        val metaString = objectMapper.writeValueAsString(meta)
        var processedString = metaString

        rowData.forEach { (key, value) ->
            processedString = processedString.replace("{{$key}}", value)
        }

        return objectMapper.readTree(processedString)
    }

    private fun buildDirectRequest(
        project: Project,
        targetUrl: String,
        meta: JsonNode,
        rowData: Map<String, String>,
        cachedAuthToken: String? = null
    ): ExecutionRequest {
        val headers = extractHeaders(meta).toMutableMap()
        cachedAuthToken?.let { headers["Authorization"] = "Bearer $it" }

        return when (project.type) {
            ProjectType.REST -> ExecutionRequest(
                targetUrl = targetUrl,
                httpMethod = HttpMethod.valueOf(meta.get("method")?.asText() ?: "POST"),
                requestType = ProjectType.REST,
                headers = headers,
                requestBody = meta.get("requestTemplate"),
                queryParams = extractQueryParams(meta)
            )

            ProjectType.SOAP -> ExecutionRequest(
                targetUrl = targetUrl,
                httpMethod = HttpMethod.POST,
                requestType = ProjectType.SOAP,
                headers = headers,
                requestBody = meta.get("requestTemplate")
            )
        }
    }

    private fun buildRestFromSoap(
        targetUrl: String,
        meta: JsonNode,
        rowData: Map<String, String>,
        cachedAuthToken: String? = null
    ): ExecutionRequest {
        // Convert SOAP template to REST JSON
        val soapTemplate = meta.get("requestTemplate")
        val jsonBody = if (soapTemplate != null) {
            executionService.convertXmlToJson(soapTemplate.asText()).fold(
                ifLeft = { null },
                ifRight = { it }
            )
        } else null

        val headers = mutableMapOf("Content-Type" to "application/json")
        cachedAuthToken?.let { headers["Authorization"] = "Bearer $it" }

        return ExecutionRequest(
            targetUrl = targetUrl,
            httpMethod = HttpMethod.POST,
            requestType = ProjectType.REST,
            headers = headers,
            requestBody = jsonBody
        )
    }

    private fun buildSoapFromRest(
        targetUrl: String,
        meta: JsonNode,
        rowData: Map<String, String>,
        cachedAuthToken: String? = null
    ): ExecutionRequest {
        // Convert REST JSON template to SOAP XML
        val jsonTemplate = meta.get("requestTemplate")
        val xmlBody = if (jsonTemplate != null) {
            executionService.convertJsonToXml(jsonTemplate).fold(
                ifLeft = { null },
                ifRight = { objectMapper.readTree("\"$it\"") }
            )
        } else null

        val headers = mutableMapOf("Content-Type" to "text/xml; charset=utf-8")
        cachedAuthToken?.let { headers["Authorization"] = "Bearer $it" }

        return ExecutionRequest(
            targetUrl = targetUrl,
            httpMethod = HttpMethod.POST,
            requestType = ProjectType.SOAP,
            headers = headers,
            requestBody = xmlBody
        )
    }

    private fun extractHeaders(meta: JsonNode): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        meta.get("headers")?.properties()?.forEach { (key, value) ->
            headers[key] = value.asText()
        }
        return headers
    }

    private fun extractQueryParams(meta: JsonNode): Map<String, String> {
        val params = mutableMapOf<String, String>()
        meta.get("queryParams")?.properties()?.forEach { (key, value) ->
            params[key] = value.asText()
        }
        return params
    }

    private fun extractAuthConfigFromProject(project: Project): AuthConfig? {
        return try {
            val authMeta = project.meta
            val node = objectMapper.valueToTree<JsonNode>(authMeta)

            val payloadNode = node.path("authPayload")
            val payloadString = payloadNode.toString()
            val audience = payloadNode.path("aud").asText(null) // extract "aud" if present

            AuthConfig(
                authHeaderKey = node.path("authHeaderKey").asText(),
                authResponseAttribute = node.path("authResponseAttribute").asText(),
                authPayload = payloadString,
                authTokenUrl = node.path("authTokenUrl").asText(),
                requiresAuth = node.path("requiresAuth").asBoolean(false),
                audience = audience
            )
        } catch (_: Exception) {
            null
        }
    }


    suspend fun getBulkExecutionStatus(id: UUID): Either<DomainError, BulkExecution> {
        return try {
            val execution = bulkExecutionRepository.findById(id)
                ?: return DomainError.NotFound("Bulk execution not found").left()

            execution.right()
        } catch (e: Exception) {
            DomainError.Database("Failed to get bulk execution status: ${e.message}").left()
        }
    }

    suspend fun generateExcelTemplate(
        projectId: UUID
    ): Either<DomainError, ByteArray> {
        return try {
            val project = projectService.findProjectById(projectId).fold(
                ifLeft = { return it.left() },
                ifRight = { it }
            )

            val workbook = XSSFWorkbook()
            val sheet = workbook.createSheet("Template")

            // Generate headers
            val headers = mutableListOf<String>()

            // Add default columns
            headers.addAll(listOf("Test Case ID", "Skip Case(Y/N)", "Description"))

            // Add request template headers
            project.requestTemplate?.let { template ->
                val requestHeaders = generateHeadersFromJson(template, "")
                headers.addAll(requestHeaders)
            }

            // Add response template headers with EXPECTED_ prefix
            project.responseTemplate?.let { template ->
                val responseHeaders = generateHeadersFromJson(template, "EXPECTED_")
                headers.addAll(responseHeaders)
            }

            // Create header row
            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { index, header ->
                headerRow.createCell(index).setCellValue(header)
            }

            // Auto-size columns
            headers.indices.forEach { sheet.autoSizeColumn(it) }

            val outputStream = ByteArrayOutputStream()
            workbook.write(outputStream)
            workbook.close()

            outputStream.toByteArray().right()
        } catch (e: Exception) {
            logger.error("Excel template generation failed", e)
            DomainError.Database("Excel template generation failed: ${e.message}").left()
        }
    }

    private fun generateHeadersFromJson(jsonNode: JsonNode, prefix: String): List<String> {
        val headers = mutableListOf<String>()
        val pathToHeader = mutableMapOf<String, String>()

        fun traverse(node: JsonNode, path: String) {
            when {
                node.isObject -> {
                    node.fields().forEach { (key, value) ->
                        val newPath = if (path.isEmpty()) key else "$path.$key"
                        traverse(value, newPath)
                    }
                }

                node.isArray && node.size() > 0 -> {
                    traverse(node[0], "$path[0]")
                }

                else -> {
                    val headerName = generateHeaderName(path, pathToHeader)
                    headers.add("$prefix$headerName")
                    pathToHeader[path] = headerName
                }
            }
        }

        traverse(jsonNode, "")
        return headers
    }

    private fun generateHeaderName(path: String, existingHeaders: Map<String, String>): String {
        val parts = path.split(".")

        // Start with the last part
        var candidate = parts.last().replace("[0]", "")

        // Check for collisions and build up the path if needed
        var depth = 1
        while (existingHeaders.values.contains(candidate) && depth <= parts.size) {
            val startIndex = maxOf(0, parts.size - depth - 1)
            candidate = parts.subList(startIndex, parts.size)
                .joinToString("_") { it.replace("[0]", "") }
            depth++
        }

        return candidate
    }
}

// Data classes for enhanced Excel processing
data class ExcelData(
    val headers: List<String>,
    val validRows: List<ExcelRowData>,
    val skippedRows: List<Int>
)

data class ExcelRowData(
    val originalRowIndex: Int,
    val data: Map<String, CellData>
)

data class CellData(
    val value: String,
    val isExcluded: Boolean = false
)

data class CachedToken(
    val token: String,
    val expiresAt: Long
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
}