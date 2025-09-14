package com.nayak.app.bulk.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.bulk.domain.BulkExecution
import com.nayak.app.bulk.domain.BulkExecutionRequest
import com.nayak.app.bulk.domain.BulkExecutionResult
import com.nayak.app.bulk.domain.BulkExecutionStatus
import com.nayak.app.bulk.domain.ConversionMode
import com.nayak.app.bulk.repo.BulkExecutionRepository
import com.nayak.app.common.errors.DomainError
import com.nayak.app.insomnia.api.InsomniaRequest
import com.nayak.app.insomnia.service.InsomniaService
import com.nayak.app.project.app.ProjectService
import com.nayak.app.project.model.Project
import com.nayak.app.project.model.ProjectType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import java.io.InputStream
import java.util.*

@Service
class BulkExecutionService(
    private val bulkExecutionRepository: BulkExecutionRepository,
    private val projectService: ProjectService,
    private val executionService: InsomniaService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(BulkExecutionService::class.java)

    suspend fun processBulkExecution(
        request: BulkExecutionRequest,
        excelFile: InputStream,
        executorId: String
    ): Either<DomainError, BulkExecution> = withContext(Dispatchers.IO) {
        try {
            // Get project details
            val project = projectService.findProjectById(request.projectId).fold(
                ifLeft = { return@withContext it.left() },
                ifRight = { it }
            )

            // Parse Excel file
            val rowData = parseExcelFile(excelFile).fold(
                ifLeft = { return@withContext it.left() },
                ifRight = { it }
            )

            // Create bulk execution record
            val bulkExecution = BulkExecution(
                projectId = request.projectId,
                executorId = executorId,
                status = BulkExecutionStatus.PENDING,
                totalRows = rowData.size
            )

            val savedExecution = bulkExecutionRepository.save(bulkExecution)

            if (request.executeImmediately) {
                // Process asynchronously
                launch {
                    processRows(savedExecution, project, rowData, request)
                }
            }

            savedExecution.right()
        } catch (e: Exception) {
            logger.error("Bulk execution setup failed", e)
            DomainError.Database("Bulk execution setup failed: ${e.message}").left()
        }
    }

    private suspend fun parseExcelFile(inputStream: InputStream): Either<DomainError, List<Map<String, String>>> {
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

            // Parse data rows
            val data = mutableListOf<Map<String, String>>()
            for (rowIndex in 1 until sheet.physicalNumberOfRows) {
                val row = sheet.getRow(rowIndex) ?: continue
                val rowData = mutableMapOf<String, String>()

                headers.forEachIndexed { colIndex, header ->
                    val cell = row.getCell(colIndex)
                    rowData[header] = cell?.toString() ?: ""
                }

                data.add(rowData)
            }

            workbook.close()
            data.right()
        } catch (e: Exception) {
            logger.error("Excel parsing failed", e)
            DomainError.Validation("Excel parsing failed: ${e.message}").left()
        }
    }

    private suspend fun processRows(
        execution: BulkExecution,
        project: Project,
        rowData: List<Map<String, String>>,
        request: BulkExecutionRequest
    ) {
        try {
            // Update status to processing
            bulkExecutionRepository.save(execution.copy(status = BulkExecutionStatus.PROCESSING))

            val results = mutableListOf<BulkExecutionResult>()
            var successCount = 0
            var failureCount = 0

            // Process each row
            rowData.forEachIndexed { index, row ->
                try {
                    val executionRequest = buildExecutionRequest(project, row, request)
                    val startTime = System.currentTimeMillis()

                    executionService.executeRequest(executionRequest).fold(
                        ifLeft = { error ->
                            results.add(BulkExecutionResult(
                                rowIndex = index,
                                success = false,
                                error = error.message,
                                executionTimeMs = System.currentTimeMillis() - startTime
                            ))
                            failureCount++
                        },
                        ifRight = { response ->
                            results.add(BulkExecutionResult(
                                rowIndex = index,
                                success = response.success,
                                statusCode = response.statusCode,
                                responseBody = response.responseBody,
                                error = if (!response.success) "HTTP ${response.statusCode}" else null,
                                executionTimeMs = response.executionTimeMs
                            ))
                            if (response.success) successCount++ else failureCount++
                        }
                    )

                    // Update progress periodically
                    if ((index + 1) % 10 == 0) {
                        bulkExecutionRepository.save(execution.copy(
                            processedRows = index + 1,
                            successfulRows = successCount,
                            failedRows = failureCount
                        ))
                    }
                } catch (e: Exception) {
                    logger.error("Row processing failed for index $index", e)
                    results.add(BulkExecutionResult(
                        rowIndex = index,
                        success = false,
                        error = "Processing error: ${e.message}"
                    ))
                    failureCount++
                }
            }

            // Final update
            bulkExecutionRepository.save(execution.copy(
                status = BulkExecutionStatus.COMPLETED,
                processedRows = rowData.size,
                successfulRows = successCount,
                failedRows = failureCount,
                results = objectMapper.valueToTree(results)
            ))

        } catch (e: Exception) {
            logger.error("Bulk execution processing failed", e)
            bulkExecutionRepository.save(execution.copy(
                status = BulkExecutionStatus.FAILED,
                errorDetails = e.message
            ))
        }
    }

    private fun buildExecutionRequest(
        project: Project,
        rowData: Map<String, String>,
        request: BulkExecutionRequest
    ): InsomniaRequest {
        // Replace placeholders in project metadata with row data
        val processedMeta = replaceTemplateVariables(project.meta, rowData)

        // Determine target URL
        val targetUrl = request.targetUrl ?: when (project.type) {
            ProjectType.REST -> processedMeta.get("baseUrl")?.asText() ?: ""
            ProjectType.SOAP -> processedMeta.get("wsdlUrl")?.asText() ?: ""
        }

        // Handle conversion modes
        return when (request.conversionMode) {
            ConversionMode.SOAP_TO_REST -> buildRestFromSoap(targetUrl, processedMeta, rowData)
            ConversionMode.REST_TO_SOAP -> buildSoapFromRest(targetUrl, processedMeta, rowData)
            ConversionMode.NONE -> buildDirectRequest(project, targetUrl, processedMeta, rowData)
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
        rowData: Map<String, String>
    ): InsomniaRequest {
        return when (project.type) {
            ProjectType.REST -> InsomniaRequest(
                targetUrl = targetUrl,
                httpMethod = HttpMethod.valueOf(meta.get("method")?.asText() ?: "POST"),
                requestType = ProjectType.REST,
                headers = extractHeaders(meta),
                requestBody = meta.get("requestTemplate"),
                queryParams = extractQueryParams(meta)
            )
            ProjectType.SOAP -> InsomniaRequest(
                targetUrl = targetUrl,
                httpMethod = HttpMethod.POST,
                requestType = ProjectType.SOAP,
                headers = extractHeaders(meta),
                requestBody = meta.get("requestTemplate")
            )
        }
    }

    private fun buildRestFromSoap(targetUrl: String, meta: JsonNode, rowData: Map<String, String>): InsomniaRequest {
        // Convert SOAP template to REST JSON
        val soapTemplate = meta.get("requestTemplate")
        val jsonBody = if (soapTemplate != null) {
            executionService.convertXmlToJson(soapTemplate.asText()).fold(
                ifLeft = { null },
                ifRight = { it }
            )
        } else null

        return InsomniaRequest(
            targetUrl = targetUrl,
            httpMethod = HttpMethod.POST,
            requestType = ProjectType.REST,
            headers = mapOf("Content-Type" to "application/json"),
            requestBody = jsonBody
        )
    }

    private fun buildSoapFromRest(targetUrl: String, meta: JsonNode, rowData: Map<String, String>): InsomniaRequest {
        // Convert REST JSON template to SOAP XML
        val jsonTemplate = meta.get("requestTemplate")
        val xmlBody = if (jsonTemplate != null) {
            executionService.convertJsonToXml(jsonTemplate).fold(
                ifLeft = { null },
                ifRight = { objectMapper.readTree("\"$it\"") }
            )
        } else null

        return InsomniaRequest(
            targetUrl = targetUrl,
            httpMethod = HttpMethod.POST,
            requestType = ProjectType.SOAP,
            headers = mapOf("Content-Type" to "text/xml; charset=utf-8"),
            requestBody = xmlBody
        )
    }

    private fun extractHeaders(meta: JsonNode): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        meta.get("headers")?.fields()?.forEach { (key, value) ->
            headers[key] = value.asText()
        }
        return headers
    }

    private fun extractQueryParams(meta: JsonNode): Map<String, String> {
        val params = mutableMapOf<String, String>()
        meta.get("queryParams")?.fields()?.forEach { (key, value) ->
            params[key] = value.asText()
        }
        return params
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
}