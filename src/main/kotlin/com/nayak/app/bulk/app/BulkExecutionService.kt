package com.nayak.app.bulk.app

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.bulk.config.BulkExcelProperties
import com.nayak.app.bulk.config.HeaderMode
import com.nayak.app.bulk.domain.*
import com.nayak.app.bulk.repo.BulkExecutionRepository
import com.nayak.app.bulk.repo.BulkExecutionResultsWriteRepository
import com.nayak.app.bulk.repo.BulkExecutionRowsWriteRepository
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
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*


@Service
class BulkExecutionService(
    private val bulkExecutionRepository: BulkExecutionRepository,
    private val jobExecutionService: JobExecutionService,
    private val projectService: ProjectService,
    private val executionService: ExecutionService,
    private val objectMapper: ObjectMapper,
    private val excelProps: BulkExcelProperties,
    private val resultsWriteRepo: BulkExecutionResultsWriteRepository,
    private val rowsWriteRepo: BulkExecutionRowsWriteRepository? = null
) {
    private val logger = LoggerFactory.getLogger(BulkExecutionService::class.java)

    private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, CachedToken>()

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
                workbook.close()
                return DomainError.Validation("Excel file must have at least a header row and one data row").left()
            }

            val formatter = DataFormatter(Locale.getDefault())
            val evaluator = workbook.creationHelper.createFormulaEvaluator()
            val isoDateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")

            // Headers
            val headerRow = sheet.getRow(0)
            val headers = (0 until headerRow.physicalNumberOfCells).map {
                headerRow.getCell(it)?.stringCellValue ?: "Column$it"
            }

            val validRows = mutableListOf<ExcelRowData>()
            val skippedRows = mutableListOf<Int>()

            for (rowIndex in 1 until sheet.physicalNumberOfRows) {
                val row = sheet.getRow(rowIndex) ?: continue

                // Skip logic
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

                        else ->
                            formatter.formatCellValue(cell, evaluator).trim()
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

            val cachedAuthToken: AuthHeader? = if (request.cacheAuthToken) getCachedAuthHeader(project) else null

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
                val rowIndex = rowData.originalRowIndex
                val testCaseId = rowData.data["Test Case ID"]?.value?.takeIf { it.isNotBlank() }
                val description = rowData.data["Description"]?.value?.takeIf { it.isNotBlank() }

                try {
                    MDC.put("rowIndex", rowIndex.toString())
                    MDC.put("currentRow", "${index + 1}/${excelData.validRows.size}")

                    // Optionally persist row header
                    rowsWriteRepo?.upsertRowHeader(
                        bulkId = execution.id!!,
                        rowIndex = rowIndex,
                        testCaseId = testCaseId,
                        description = description
                    )

                    val executionRequest = buildExecutionRequest(project, rowData.data, request, cachedAuthToken)

                    // Serialize request body (JsonNode?) to compact string for DB write
                    val requestBodyJsonString =
                        executionRequest.requestBody?.let { objectMapper.writeValueAsString(it) }

                    // Record the request up-front (so we have it even if HTTP fails)
                    resultsWriteRepo.upsertRequest(
                        bulkId = execution.id!!,
                        rowIndex = rowIndex,
                        testCaseId = testCaseId,
                        description = description,
                        requestBody = requestBodyJsonString
                    )

                    val start = System.currentTimeMillis()

                    executionService.executeRequest(executionRequest).fold(
                        ifLeft = { error ->
                            val execMs = (System.currentTimeMillis() - start).toInt()
                            logger.warn("Row execution failed: rowIndex={}, error={}", rowIndex, error.message)
                            logFailedRequest(executionRequest, error.message ?: "error")

                            resultsWriteRepo.upsertFailure(
                                bulkId = execution.id!!,
                                rowIndex = rowIndex,
                                testCaseId = testCaseId,
                                description = description,
                                success = false,
                                error = error.message ?: "Execution error",
                                executionTimeMs = execMs
                            )
                            failureCount++
                        },
                        ifRight = { resp ->
                            val execMs = resp.executionTimeMs

                            val responseBodyJsonString =
                                resp.responseBody?.let { objectMapper.writeValueAsString(it) }

                            resultsWriteRepo.patchWithResponse(
                                bulkId = execution.id!!,
                                rowIndex = rowIndex,
                                responseBody = responseBodyJsonString,
                                statusCode = resp.statusCode,
                                success = resp.success,
                                error = if (resp.success) null else "HTTP ${resp.statusCode}",
                                executionTimeMs = execMs.toInt()
                            )
                            if (resp.success) successCount++ else failureCount++
                        }
                    )

                    // Progress every 10 rows (unchanged)
                    if ((index + 1) % 10 == 0) {
                        jobExecutionService.updateJobProgress(
                            executionId, JobProgressInfo(
                                totalItems = excelData.validRows.size,
                                processedItems = index + 1,
                                successfulItems = successCount,
                                failedItems = failureCount,
                                currentItem = "Row $rowIndex"
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
                    val execMs = null // unknown
                    logger.error("Row processing failed: rowIndex={}, index={}", rowIndex, index, e)
                    resultsWriteRepo.upsertFailure(
                        bulkId = execution.id!!,
                        rowIndex = rowIndex,
                        testCaseId = testCaseId,
                        description = description,
                        success = false,
                        error = "Processing error: ${e.message}",
                        executionTimeMs = execMs
                    )
                    failureCount++
                } finally {
                    MDC.remove("rowIndex"); MDC.remove("currentRow")
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
                    // results = objectMapper.valueToTree(results)
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
                    // results untouched
                )
            )
        }
    }

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

    private suspend fun getCachedAuthHeader(project: Project): AuthHeader? {
        return try {
            val authConfig = extractAuthConfigFromProject(project)
            if (authConfig?.requiresAuth != true) return null

            // Build robust cache key
            val payloadHash = authConfig.authPayload.hashCode().toString()
            val cacheKey = listOfNotNull(
                authConfig.authTokenUrl,
                authConfig.audience,
                authConfig.authHeaderKey,
                payloadHash
            ).joinToString("|")

            // Hit cache
            tokenCache[cacheKey]?.takeIf { !it.isExpired() }?.let { cached ->
                return AuthHeader(authConfig.authHeaderKey, cached.token)
            }

            // Fetch fresh token
            val tokenOrNull = executionService.getAuthToken(authConfig).fold(
                ifLeft = { null },
                ifRight = { it } // assuming this is the raw token string
            )

            if (tokenOrNull == null) return null

            // Decide the final header value. If you need "Bearer ", add it here based on config.
            val finalHeaderValue = when {
                // If project/meta sets a prefix, prefer that (not shown — add to AuthConfig if you have it)
                // else default to raw token:
                else -> tokenOrNull
            }

            // If you can extract real expiry from token response, use that; else conservative default.
            val defaultTtl = 55 * 60 * 1000L
            val expiresAt = System.currentTimeMillis() + defaultTtl - 60_000

            tokenCache[cacheKey] = CachedToken(finalHeaderValue, expiresAt)
            AuthHeader(authConfig.authHeaderKey, finalHeaderValue)
        } catch (e: Exception) {
            logger.warn("Failed to get cached auth token", e)
            null
        }
    }

    private fun buildExecutionRequest(
        project: Project,
        rowData: Map<String, CellData>,
        request: BulkExecutionRequest,
        cachedAuthToken: AuthHeader? = null
    ): ExecutionRequest {
        // Filter out excluded cells and replace placeholders
        val filteredRowData = rowData.filterNot { it.value.isExcluded }.mapValues { it.value.value }

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
//                filteredRowData,
                rowData.filterValues { !it.isExcluded },
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
        rowData: Map<String, CellData>,
        cachedAuthHeader: AuthHeader? = null
    ): ExecutionRequest {
        val headers = extractHeaders(meta).toMutableMap()
        cachedAuthHeader?.let { headers[it.key] = it.value }
        // Reconstruct request body from rowData using the request template structure
        val requestBody = project.requestTemplate?.let { template ->
            reconstructJsonFromRowData(template, rowData, "")
        } ?: meta.get("requestTemplate")

        return when (project.type) {
            ProjectType.REST -> ExecutionRequest(
                targetUrl = targetUrl,
                httpMethod = HttpMethod.valueOf(meta.get("method")?.asText() ?: "POST"),
                requestType = ProjectType.REST,
                headers = headers,
                requestBody = requestBody,
                queryParams = extractQueryParams(meta)
            )

            ProjectType.SOAP -> ExecutionRequest(
                targetUrl = targetUrl,
                httpMethod = HttpMethod.POST,
                requestType = ProjectType.SOAP,
                headers = headers,
                requestBody = requestBody
            )
        }
    }

    private fun buildRestFromSoap(
        targetUrl: String,
        meta: JsonNode,
        rowData: Map<String, String>,
        cachedAuthToken: AuthHeader? = null
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
        cachedAuthToken?.let { headers[it.key] = it.value }

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
        cachedAuthToken: AuthHeader? = null
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
        cachedAuthToken?.let { headers[it.key] = it.value }

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

    private data class HeaderSpec(
        val headers: List<String>,
        val pathToHeader: Map<String, String>
    )

    private fun buildHeaderSpec(
        jsonNode: JsonNode,
        prefix: String = excelProps.headers.prefix,
        useDotNotation: Boolean = (excelProps.headers.mode == HeaderMode.DOT)
    ): HeaderSpec {
        val headers = mutableListOf<String>()
        val pathToHeader = mutableMapOf<String, String>()

        fun generateHeaderName(
            path: String,
            existing: Map<String, String>,
            useDot: Boolean
        ): String {
            if (useDot) return path
            val parts = path.split(".")
            var candidate = parts.last().replace("[0]", "")
            var depth = 1
            while (existing.values.contains(candidate) && depth <= parts.size) {
                val startIndex = maxOf(0, parts.size - depth - 1)
                candidate = parts.subList(startIndex, parts.size)
                    .joinToString("_") { it.replace("[0]", "") }
                depth++
            }
            return candidate
        }

        fun traverse(node: JsonNode, path: String) {
            when {
                node.isObject -> {
                    node.fields().forEach { (k, v) ->
                        val np = if (path.isEmpty()) k else "$path.$k"
                        traverse(v, np)
                    }
                }

                node.isArray -> {
                    if (excelProps.array.firstElementOnly) {
                        if (node.size() > 0) traverse(node[0], "$path[0]")
                    } else {
                        for (i in 0 until node.size()) traverse(node[i], "$path[$i]")
                    }
                }

                else -> {
                    val name = generateHeaderName(path, pathToHeader, useDotNotation)
                    pathToHeader[path] = name
                    headers += "$prefix$name"
                }
            }
        }

        traverse(jsonNode, "")
        return HeaderSpec(headers, pathToHeader)
    }

    private fun flattenToRowValues(
        node: JsonNode?,
        spec: HeaderSpec,
        requiresHeaderPrefix: Boolean = true
    ): Map<String, String> {
        if (node == null) return emptyMap()
        val out = mutableMapOf<String, String>()

        fun put(path: String, value: JsonNode) {
            val header = spec.pathToHeader[path] ?: return
            val s = when {
                value.isTextual -> value.asText()
                value.isNumber || value.isBoolean || value.isNull -> value.toString()
                else -> objectMapper.writeValueAsString(value) // object/array -> compact JSON
            }
            if (requiresHeaderPrefix) {
                out["${excelProps.headers.prefix}$header".replaceFirst(
                    excelProps.headers.prefix,
                    ""
                ) // already included in spec
                    .let { if (excelProps.headers.prefix.isNotEmpty()) excelProps.headers.prefix + it else it }
                ] = s
            } else {
                out[header] = s
            }

        }

        fun walk(n: JsonNode, path: String) {
            when {
                n.isObject -> n.fields().forEach { (k, v) ->
                    val np = if (path.isEmpty()) k else "$path.$k"
                    walk(v, np)
                }

                n.isArray -> {
                    if (excelProps.array.firstElementOnly) {
                        if (n.size() > 0) walk(n[0], "$path[0]")
                    } else {
                        for (i in 0 until n.size()) walk(n[i], "$path[$i]")
                    }
                }

                else -> put(path, n)
            }
        }

        walk(node, "")
        return out
    }

    suspend fun generateExcelTemplate(
        projectId: UUID,
        includeValues: Boolean = false
    ): Either<DomainError, ByteArray> = try {
        val project = projectService.findProjectById(projectId).fold(
            ifLeft = { return it.left() },
            ifRight = { it }
        )

        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Template")

        // 1) default columns
        val baseHeaders = listOf("Test Case ID", "Skip Case(Y/N)", "Description")

        // 2) request headers + mapping
        val reqSpec = project.requestTemplate?.let { buildHeaderSpec(it, prefix = "") }
        // 3) response headers + mapping (EXPECTED_)
        val respSpec = project.responseTemplate?.let { buildHeaderSpec(it, prefix = "EXPECTED_") }

        // 4) Create header row
        val headers = buildList {
            addAll(baseHeaders)
            reqSpec?.headers?.let(::addAll)
            respSpec?.headers?.let(::addAll)
        }

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h -> headerRow.createCell(i).setCellValue(h) }

        // 5) Optionally add one data row filled with defaults from templates
        if (includeValues) {
            val row = sheet.createRow(1)

            // base columns defaults
            row.createCell(0).setCellValue("") // Test Case ID
            row.createCell(1).setCellValue("") // Skip Case(Y/N)
            row.createCell(2).setCellValue("") // Description

            // flatten request
            val reqVals = reqSpec?.let { flattenToRowValues(project.requestTemplate, it, false) } ?: emptyMap()
            // flatten expected response
            val respVals = respSpec?.let { flattenToRowValues(project.responseTemplate, it) } ?: emptyMap()

            // place values by header index
            val values = reqVals + respVals
            headers.forEachIndexed { idx, h ->
                if (idx < 3) return@forEachIndexed
                values[h]?.let { row.createCell(idx).setCellValue(it) }
            }
        }

        // 6) autosize
        headers.indices.forEach { sheet.autoSizeColumn(it) }

        val bos = ByteArrayOutputStream()
        workbook.write(bos)
        workbook.close()
        bos.toByteArray().right()
    } catch (e: Exception) {
        logger.error("Excel template generation failed", e)
        DomainError.Database("Excel template generation failed: ${e.message}").left()
    }


    private fun generateHeadersFromJson(
        jsonNode: JsonNode,
        prefix: String = excelProps.headers.prefix,
        useDotNotation: Boolean = (excelProps.headers.mode == HeaderMode.DOT)
    ): List<String> {
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

                node.isArray -> {
                    // When firstElementOnly = true, keep legacy behavior ([0] only)
                    if (excelProps.array.firstElementOnly) {
                        if (node.size() > 0) traverse(node[0], "$path[0]")
                    } else {
                        // Emit headers for each index that exists in the template JSON
                        for (i in 0 until node.size()) {
                            traverse(node[i], "$path[$i]")
                        }
                    }
                }

                else -> {
                    val headerName = generateHeaderName(path, pathToHeader, useDotNotation)
                    headers.add("$prefix$headerName")
                    pathToHeader[path] = headerName
                }
            }
        }

        traverse(jsonNode, "")
        return headers
    }

    private fun generateHeaderName(
        path: String,
        existingHeaders: Map<String, String>,
        useDotNotation: Boolean
    ): String {
        if (useDotNotation) return path

        val parts = path.split(".")
        var candidate = parts.last().replace("[0]", "")

        var depth = 1
        while (existingHeaders.values.contains(candidate) && depth <= parts.size) {
            val startIndex = maxOf(0, parts.size - depth - 1)
            candidate = parts.subList(startIndex, parts.size)
                .joinToString("_") { it.replace("[0]", "") }
            depth++
        }
        return candidate
    }

    /**
     * Reconstructs a JSON structure from flat Excel row data, coercing values
     * to the types defined by the corresponding leaf in the request template.
     *
     * - Objects: recurse field-by-field.
     * - Arrays: reuses the template's first element shape (index [0]) and constructs a single element array,
     *           consistent with your current header derivation strategy.
     * - Leaves: coerce the string to template type (int/long/decimal/boolean/string/null).
     *
     * If a value is not provided in rowData, the original template value is kept.
     */
    /**
     * Reconstructs JSON from flat Excel row data (CellData) using:
     *  1) Excel cell type hints (NUMERIC/BOOLEAN/DATE/STRING/BLANK/FORMULA), and
     *  2) The request template leaf type (int/long/decimal/boolean/string/null).
     *
     * Priority:
     *  - If Excel says NUMERIC/BOOLEAN/DATE, coerce accordingly.
     *  - Then align to the template leaf type (e.g., NUMERIC -> INT/LONG vs DECIMAL).
     *  - If no override provided for a leaf, keep the template's original value.
     */
    private fun reconstructJsonFromRowData(
        template: JsonNode,
        rowData: Map<String, CellData>,
        prefix: String = excelProps.headers.prefix,
        useDotNotation: Boolean = (excelProps.headers.mode == HeaderMode.DOT)
    ): JsonNode {
        val pathToHeader = mutableMapOf<String, String>()
        val pathToValue = mutableMapOf<String, String>()
        val pathToHint = mutableMapOf<String, String?>()
        val arrayIndexMap = mutableMapOf<String, MutableSet<Int>>() // basePath -> indices

        fun registerLeaf(path: String) {
            val headerName = generateHeaderName(path, pathToHeader, useDotNotation)
            pathToHeader[path] = headerName
            val fullHeader = "$prefix$headerName"
            rowData[fullHeader]?.let { cd ->
                pathToValue[path] = cd.value
                pathToHint[path] = cd.typeHint
            }
        }

        fun scanTemplate(node: JsonNode, path: String) {
            when {
                node.isObject -> node.fields().forEach { (k, v) ->
                    val np = if (path.isEmpty()) k else "$path.$k"
                    scanTemplate(v, np)
                }

                node.isArray -> {
                    if (excelProps.array.firstElementOnly) {
                        if (node.size() > 0) scanTemplate(node[0], "$path[0]")
                    } else {
                        for (i in 0 until node.size()) scanTemplate(node[i], "$path[$i]")
                    }
                }

                else -> registerLeaf(path)
            }
        }
        scanTemplate(template, "")

        // In DOT mode we can discover extra indices directly from headers (prefix + dot path)
        if (useDotNotation && !excelProps.array.firstElementOnly) {
            rowData.keys.asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) } // now a DOT path like "a.b[2].c[1].d"
                .forEach { fullPath ->
                    collectIndicesFromDotPath(fullPath, arrayIndexMap)
                }
        }

        fun reconstruct(node: JsonNode, path: String): JsonNode =
            when {
                node.isObject -> {
                    val obj = objectMapper.createObjectNode()
                    node.fields().forEach { (k, v) ->
                        val np = if (path.isEmpty()) k else "$path.$k"
                        obj.set<JsonNode>(k, reconstruct(v, np))
                    }
                    obj
                }

                node.isArray -> {
                    val arr = objectMapper.createArrayNode()
                    if (excelProps.array.firstElementOnly) {
                        if (node.size() > 0) arr.add(reconstruct(node[0], "$path[0]"))
                    } else {
                        // indices to materialize: from discovered DOT headers (preferred), else template size
                        val indices = arrayIndexMap[path]?.sorted()?.toList()
                            ?: (0 until node.size()).toList()
                        for (i in indices) {
                            val elemTemplate =
                                if (node.size() > 0) node[minOf(i, node.size() - 1)] else objectMapper.nullNode()
                            arr.add(reconstruct(elemTemplate, "$path[$i]"))
                        }
                    }
                    arr
                }

                else -> {
                    val raw = pathToValue[path]
                    if (raw != null) {
                        val hint = pathToHint[path]
                        coerceByHintThenTemplate(raw, hint, node)  // your existing coercion
                    } else node
                }
            }

        return reconstruct(template, "")
    }

    /** Parse all [n] occurrences from a DOT path and record them per base path. */
    private fun collectIndicesFromDotPath(
        dotPath: String,
        sink: MutableMap<String, MutableSet<Int>>
    ) {
        // Walk through e.g. "a.b[2].c[1].d" and record:
        // base "a.b" -> 2, base "a.b[2].c" -> 1 (nested arrays supported)
        var i = 0
        while (true) {
            val lb = dotPath.indexOf('[', i)
            if (lb == -1) break
            val rb = dotPath.indexOf(']', lb + 1)
            if (rb == -1) break
            val base = dotPath.substring(0, lb)             // base path before this index
            val idx = dotPath.substring(lb + 1, rb).toIntOrNull()
            if (idx != null) sink.getOrPut(base) { mutableSetOf() }.add(idx)
            i = rb + 1
        }
    }


    /**
     * Coerces a string from Excel into the type of the given template leaf node.
     * Supports:
     *   - Integer types (INT, LONG, BIG_INTEGER): accepts "2", "2.0" (as 2), rejects non-whole decimals (logs warning)
     *   - Decimal types (FLOAT, DOUBLE, BIG_DECIMAL): parses as BigDecimal
     *   - Boolean: accepts true/false/1/0/yes/no (case-insensitive)
     *   - String: returns as text
     *   - Null (template null): empty -> null, else fall back to typed inference (string if nothing matches)
     */
    private fun coerceStringToTemplateType(value: String, templateLeaf: JsonNode): JsonNode {
        val f = objectMapper.nodeFactory
        val trimmed = value.trim()

        // Common "empty" handling
        if (trimmed.isEmpty()) return f.nullNode()

        // If template says null, try to infer; if "null" literal, keep null
        if (templateLeaf.isNull) {
            if (trimmed.equals("null", ignoreCase = true)) return f.nullNode()
            // Fall through to a best-effort inference; use string if nothing fits
        }

        // If template is boolean, parse loosely
        if (templateLeaf.isBoolean) {
            parseBooleanLoose(trimmed)?.let { return f.booleanNode(it) }
            // If it doesn't parse as boolean, fall back to string to avoid silent lies
            return f.textNode(trimmed)
        }

        // If template is numeric, match its numeric kind
        if (templateLeaf.isNumber) {
            val numberType = templateLeaf.numberType()  // INT, LONG, DOUBLE, BIG_DECIMAL, etc.
            val bd = trimmed.toBigDecimalOrNull()

            if (bd != null) {
                when (numberType) {
                    com.fasterxml.jackson.core.JsonParser.NumberType.INT -> {
                        // Accept only whole numbers; coerce 2.0 -> 2
                        val whole = bd.stripTrailingZeros().scale() <= 0
                        if (whole) {
                            val asLong = bd.longValueExactOrNull()
                            return if (asLong != null && asLong in Int.MIN_VALUE..Int.MAX_VALUE)
                                f.numberNode(asLong.toInt())
                            else {
                                // Out of int range: log & use long
                                logger.warn("Value '{}' exceeds Int range; using Long", trimmed)
                                f.numberNode((asLong ?: bd.toLong()))
                            }
                        } else {
                            logger.warn("Non-whole '{}' for INT template; keeping template value", trimmed)
                            return templateLeaf
                        }
                    }

                    com.fasterxml.jackson.core.JsonParser.NumberType.LONG,
                    com.fasterxml.jackson.core.JsonParser.NumberType.BIG_INTEGER -> {
                        val whole = bd.stripTrailingZeros().scale() <= 0
                        if (whole) {
                            val asLong = bd.longValueExactOrNull() ?: bd.toLong()
                            return f.numberNode(asLong)
                        } else {
                            logger.warn("Non-whole '{}' for LONG template; keeping template value", trimmed)
                            return templateLeaf
                        }
                    }

                    com.fasterxml.jackson.core.JsonParser.NumberType.FLOAT,
                    com.fasterxml.jackson.core.JsonParser.NumberType.DOUBLE,
                    com.fasterxml.jackson.core.JsonParser.NumberType.BIG_DECIMAL -> {
                        // Keep high precision; caller/request serialization will choose float/double as needed
                        return f.numberNode(bd)
                    }

                    else -> {
                        // Fallback: just use BigDecimal
                        return f.numberNode(bd)
                    }
                }
            } else {
                // Not a number; keep template to avoid lying
                logger.warn("Non-numeric '{}' for numeric template; keeping template value", trimmed)
                return templateLeaf
            }
        }

        // If template is textual, respect that (including date/time strings, enums, etc.)
        if (templateLeaf.isTextual) {
            // Treat "null" specially
            if (trimmed.equals("null", ignoreCase = true)) return f.nullNode()
            return f.textNode(trimmed)
        }

        // For other node types (binary, POJO, etc.), default to text to avoid losing data
        return f.textNode(trimmed)
    }

    /** Loose boolean parsing: true/false/1/0/yes/no (case-insensitive). */
    private fun parseBooleanLoose(s: String): Boolean? = when (s.lowercase()) {
        "true", "t", "yes", "y", "1" -> true
        "false", "f", "no", "n", "0" -> false
        else -> null
    }

    /** BigDecimal.longValueExact but null-safe */
    private fun java.math.BigDecimal.longValueExactOrNull(): Long? = try {
        this.toBigIntegerExact().longValueExact()
    } catch (_: ArithmeticException) {
        null
    }

    /**
     * First coerce by Excel hint (NUMERIC/BOOLEAN/DATE/STRING/BLANK/FORMULA),
     * then align to template leaf type (int/long/decimal/boolean/string/null).
     */
    private fun coerceByHintThenTemplate(raw: String, hint: String?, templateLeaf: JsonNode): JsonNode {
        val f = objectMapper.nodeFactory
        val s = raw.trim()
        if (s.isEmpty() || s.equals("null", ignoreCase = true)) return f.nullNode()

        // Step 1: Excel hint coercion
        val hintedNode: JsonNode? = when (hint) {
            "BOOLEAN" -> parseBooleanLoose(s)?.let { f.booleanNode(it) }
            "DATE" -> f.textNode(s) // we already normalized dates to ISO strings upstream
            "NUMERIC" -> s.toBigDecimalOrNull()?.let { f.numberNode(it) }
            "STRING", "BLANK" -> f.textNode(s)
            "FORMULA" -> {
                // We only got the formatted result string; try number->boolean->text order.
                s.toBigDecimalOrNull()?.let { f.numberNode(it) }
                    ?: parseBooleanLoose(s)?.let { f.booleanNode(it) }
                    ?: f.textNode(s)
            }

            else -> null
        }

        // If no hint (or unrecognized), try a reasonable default: number -> boolean -> string
        val excelNode = hintedNode ?: (
                s.toBigDecimalOrNull()?.let { f.numberNode(it) }
                    ?: parseBooleanLoose(s)?.let { f.booleanNode(it) }
                    ?: f.textNode(s)
                )

        // Step 2: Align with template leaf type
        // - If template is textual: keep as text (avoid turning IDs like "0012" into number).
        if (templateLeaf.isTextual) {
            return f.textNode(s)
        }

        // - If template is boolean: force boolean (fallback to template value if unparsable).
        if (templateLeaf.isBoolean) {
            return parseBooleanLoose(s)?.let { f.booleanNode(it) } ?: templateLeaf
        }

        // - If template is null: keep excelNode (already coerced reasonably).
        if (templateLeaf.isNull) {
            return excelNode
        }

        // - If template is numeric: match its numeric kind (INT/LONG/BIG_INTEGER vs DECIMAL family).
        if (templateLeaf.isNumber) {
            val numType = templateLeaf.numberType()
            val bd = s.toBigDecimalOrNull() ?: return templateLeaf // not a number: keep template

            return when (numType) {
                com.fasterxml.jackson.core.JsonParser.NumberType.INT -> {
                    val whole = bd.stripTrailingZeros().scale() <= 0
                    if (!whole) return templateLeaf // refuse 2.5 for INT
                    val asLong = bd.longValueExactOrNull() ?: bd.toLong()
                    if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) f.numberNode(asLong.toInt())
                    else {
                        logger.warn("Value '{}' exceeds Int range; using Long in place of Int", s)
                        f.numberNode(asLong)
                    }
                }

                com.fasterxml.jackson.core.JsonParser.NumberType.LONG,
                com.fasterxml.jackson.core.JsonParser.NumberType.BIG_INTEGER -> {
                    val whole = bd.stripTrailingZeros().scale() <= 0
                    if (!whole) return templateLeaf // refuse 2.5 for LONG
                    val asLong = bd.longValueExactOrNull() ?: bd.toLong()
                    f.numberNode(asLong)
                }

                com.fasterxml.jackson.core.JsonParser.NumberType.FLOAT,
                com.fasterxml.jackson.core.JsonParser.NumberType.DOUBLE,
                com.fasterxml.jackson.core.JsonParser.NumberType.BIG_DECIMAL -> {
                    // Preserve exact decimal; Jackson will serialize as JSON number
                    f.numberNode(bd)
                }

                else -> f.numberNode(bd)
            }
        }

        // For any other rare node kinds, return the best-effort Excel-coerced node
        return excelNode
    }

}

data class AuthHeader(val key: String, val value: String)

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
    val isExcluded: Boolean = false,
    val typeHint: String? = null // e.g. "STRING", "NUMERIC", "BOOLEAN", "DATE", "BLANK", "FORMULA"
)


data class CachedToken(
    val token: String,
    val expiresAt: Long
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt
}