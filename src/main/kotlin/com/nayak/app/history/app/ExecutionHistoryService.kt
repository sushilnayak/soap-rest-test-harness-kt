package com.nayak.app.history.app

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.bulk.domain.BulkExecution
import com.nayak.app.bulk.repo.BulkExecutionRepository
import com.nayak.app.bulk.repo.BulkExecutionRowProjection
import com.nayak.app.bulk.repo.RowBodyProjection
import com.nayak.app.common.errors.DomainError
import com.nayak.app.history.domain.ExecutionArtifact
import com.nayak.app.history.domain.ExecutionHistoryItemDto
import com.nayak.app.history.domain.ExecutionHistoryItemDto.BulkDetails
import com.nayak.app.history.domain.ExecutionType
import com.nayak.app.history.domain.HistorySearchType
import com.nayak.app.project.app.PagedResult
import com.nayak.app.project.app.ProjectService
import kotlinx.coroutines.flow.toList
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Service
class ExecutionHistoryService(
    private val bulkExecutionRepository: BulkExecutionRepository,
    private val projectService: ProjectService,
    private val objectMapper: ObjectMapper
) {
    private val zipDateFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    private val logger = LoggerFactory.getLogger(ExecutionHistoryService::class.java)

    @Transactional(readOnly = true)
    suspend fun listHistoryPaginated(
        search: HistorySearchType,
        page: Int,
        size: Int,
        userId: String
    ): Either<DomainError, PagedResult<ExecutionHistoryItemDto>> = either {
        validatePagination(
            page,
            size
        )
        val offset = page.toLong() * size.toLong()
        val total: Long =
            Either.catch { bulkExecutionRepository.countBySearchType(racfId = if (search == HistorySearchType.ALL) null else userId) }
                .mapLeft { th -> DomainError.Database("Failed to count Execution History: ${th.message}") }
                .bind()

        val totalPages = ((total + size.toLong() - 1L) / size.toLong()).toInt()

        if (offset >= total && total > 0L) {
            return@either PagedResult(
                content = emptyList(),
                page = page,
                size = size,
                totalElements = total,
                totalPages = totalPages
            )
        }
        val executionHistories: List<BulkExecution> = Either.catch {
            bulkExecutionRepository.findAllPaginated(
                racfId = if (search == HistorySearchType.ALL) null else userId,
                limit = size,
                offset = offset
            )
                .toList() // Flow<Project> -> List<Project>
        }
            .mapLeft { th -> DomainError.Database("Failed to fetch page $page: ${th.message}") }
            .bind()

        val executionHistoriesDtos = executionHistories.map { it.toViewDto() }

        PagedResult(
            content = executionHistoriesDtos,
            page = page,
            size = size,
            totalElements = total,
            totalPages = totalPages
        )
    }


    suspend fun getDetails(executionId: UUID): Either<DomainError, List<BulkExecutionRowProjection>> = either {

        val exists = bulkExecutionRepository.existsById(executionId)
        ensure(exists) { DomainError.NotFound("No bulk execution found with id=$executionId") }

        val rows = try {
            bulkExecutionRepository.findRowsByBulkId(executionId).toList()
        } catch (e: Exception) {
            raise(DomainError.Database("Failed to load execution details: ${e.message}"))
        }

        rows
    }

    suspend fun getRowRequestBody(executionId: UUID, rowIndex: Int): Either<DomainError, String> = either {
        val exists = bulkExecutionRepository.existsById(executionId)
        ensure(exists) { DomainError.NotFound("No bulk execution found with id=$executionId") }

        val body = bulkExecutionRepository.findRequestBodyByBulkIdAndRowIndex(executionId, rowIndex)
        ensure(!body.isNullOrBlank()) {
            DomainError.NotFound("Request body not present for execution=$executionId row=$rowIndex")
        }
        body
    }

    suspend fun getRowResponseBody(executionId: UUID, rowIndex: Int): Either<DomainError, String> = either {
        val exists = bulkExecutionRepository.existsById(executionId)
        ensure(exists) { DomainError.NotFound("No bulk execution found with id=$executionId") }

        val body = bulkExecutionRepository.findResponseBodyByBulkIdAndRowIndex(executionId, rowIndex)
        ensure(!body.isNullOrBlank()) {
            DomainError.NotFound("Response body not present for execution=$executionId row=$rowIndex")
        }
        body
    }

    suspend fun downloadAllRequestsZip(executionId: UUID): Either<DomainError, Pair<String, ByteArray>> = either {
        ensure(bulkExecutionRepository.existsById(executionId)) {
            DomainError.NotFound("No bulk execution found with id=$executionId")
        }
        val createdAt = bulkExecutionRepository.findCreatedAt(executionId)
            ?: raise(DomainError.Database("created_at not found for id=$executionId"))

        val rows = bulkExecutionRepository.streamAllRequests(executionId).toList()

        val bytes = buildZip(
            rows = rows,
            filePrefix = "request",
            defaultBody = "{}"
        )

        val fileName = "execution_${executionId}_${zipDateFmt.format(createdAt)}_requests.zip"
        fileName to bytes
    }

    suspend fun downloadAllResponsesZip(executionId: UUID): Either<DomainError, Pair<String, ByteArray>> = either {
        ensure(bulkExecutionRepository.existsById(executionId)) {
            DomainError.NotFound("No bulk execution found with id=$executionId")
        }
        val createdAt = bulkExecutionRepository.findCreatedAt(executionId)
            ?: raise(DomainError.Database("created_at not found for id=$executionId"))

        val rows = bulkExecutionRepository.streamAllResponses(executionId).toList()

        val bytes = buildZip(
            rows = rows,
            filePrefix = "response",
            defaultBody = "{}"
        )

        val fileName = "execution_${executionId}_${zipDateFmt.format(createdAt)}_responses.zip"
        fileName to bytes
    }

    suspend fun exportBulkExcel(
        bulkId: UUID,
        withResults: Boolean = false
    ): Either<DomainError, ByteArray> = either {

        val payloadJson = bulkExecutionRepository.findJobPayloadByBulkId(bulkId)
            ?: raise(DomainError.NotFound("job_payload not found for $bulkId"))

        val payloadNode = objectMapper.readTree(payloadJson)
        val excelData = payloadNode.path("excelData")
        val headersFromPayload = excelData.path("headers").map { it.asText() }.toMutableList()

        if (headersFromPayload.isEmpty()) {
            val firstData = excelData.path("validRows").firstOrNull()?.path("data")
            if (firstData != null && firstData.isObject) {
                headersFromPayload += firstData.fieldNames().asSequence().toList()
            }
        }

        val rows = excelData.path("validRows").map { row ->
            val idx = row.path("originalRowIndex").asInt()
            val data = row.path("data")
            val map = mutableMapOf<String, String?>()
            headersFromPayload.forEach { h ->
                val v = data.path(h).path("value").asText("")
                map[h] = v
            }
            idx to map
        }.sortedBy { it.first }

        val actualHeaderSet = linkedSetOf<String>()
        val rowIndexToActual: MutableMap<Int, Map<String, String?>> =
            if (withResults) LinkedHashMap(rows.size) else LinkedHashMap(0)

        if (withResults) {

            bulkExecutionRepository.streamAllResponses(bulkId).collect { proj ->
                val idx = proj.rowIndex
                val bodyStr = proj.body?.trim()
                if (!bodyStr.isNullOrEmpty()) {
                    val bodyNode = safeParseJson(bodyStr) ?: return@collect
                    val flat = flattenJson(bodyNode) // e.g. "user.id" , "items[0].sku"
                    val prefixed = flat.mapKeys { (k, _) -> "ACTUAL_$k" }
                    prefixed.keys.forEach(actualHeaderSet::add)
                    rowIndexToActual[idx] = prefixed
                }
            }
        }

        //  Compose final headers (Request -> EXPECTED_ -> ACTUAL_)
        val mandatory = listOf("Test Case ID", "Skip Case(Y/N)", "Description")
        val requestHeaders = headersFromPayload.filter { h ->
            !h.startsWith("EXPECTED_") && !mandatory.contains(h)
        }
        val expectedHeaders = headersFromPayload.filter { it.startsWith("EXPECTED_") }
        val actualHeaders = if (withResults) actualHeaderSet.toList() else emptyList()

        val finalHeaders = buildList {
            addAll(mandatory)
            addAll(requestHeaders)
            addAll(expectedHeaders)
            addAll(actualHeaders)
        }

        val wb: Workbook = SXSSFWorkbook(200)
        val sheet = wb.createSheet("Results")
        val styles = createColumnStyles(wb)

        val colKind: List<ColKind> = finalHeaders.map { h ->
            when {
                mandatory.contains(h) -> ColKind.MANDATORY
                h.startsWith("EXPECTED_") -> ColKind.EXPECTED
                h.startsWith("ACTUAL_") -> ColKind.ACTUAL
                else -> ColKind.REQUEST
            }
        }

        run {
            val r0 = sheet.createRow(0)
            finalHeaders.forEachIndexed { i, h ->
                val c = r0.createCell(i)
                c.setCellValue(h)
                c.cellStyle = when (colKind[i]) {
                    ColKind.MANDATORY -> styles.headerMandatory
                    ColKind.REQUEST -> styles.headerRequest
                    ColKind.EXPECTED -> styles.headerExpected
                    ColKind.ACTUAL -> styles.headerActual
                }
            }
        }

        rows.forEachIndexed { i, (rowIndex, reqMap) ->
            val r = sheet.createRow(i + 1)
            finalHeaders.forEachIndexed { col, header ->
                val v: String? = when {
                    // request side (mandatory+request): take from payload map
                    !header.startsWith("EXPECTED_") && !header.startsWith("ACTUAL_") -> reqMap[header]
                    header.startsWith("EXPECTED_") -> reqMap[header] // EXPECTED_ already present in payload
                    else -> rowIndexToActual[rowIndex]?.get(header)  // ACTUAL_ from flattened response
                }
                val cell = r.createCell(col)
                cell.setCellValue(v ?: "")
                cell.cellStyle = when (colKind[col]) {
                    ColKind.MANDATORY -> styles.dataMandatory
                    ColKind.REQUEST -> styles.dataRequest
                    ColKind.EXPECTED -> styles.dataExpected
                    ColKind.ACTUAL -> styles.dataActual
                }
            }
        }

        // autosize a sane subset
        finalHeaders.take(50).indices.forEach { kotlin.runCatching { sheet.autoSizeColumn(it) } }

        val out = ByteArrayOutputStream()
        wb.write(out)
        (wb as? SXSSFWorkbook)?.dispose()
        out.toByteArray()
    }.mapLeft {
        logger.error("Failed to export excel for bulkId={}", bulkId, it)
        it
    }

    private data class ColumnStyles(
        val headerMandatory: CellStyle,
        val headerRequest: CellStyle,
        val headerExpected: CellStyle,
        val headerActual: CellStyle,
        val dataMandatory: CellStyle,
        val dataRequest: CellStyle,
        val dataExpected: CellStyle,
        val dataActual: CellStyle
    )

    private fun createColumnStyles(wb: Workbook): ColumnStyles {
        fun header(fill: Short): CellStyle = wb.createCellStyle().apply {
            fillForegroundColor = fill
            fillPattern = FillPatternType.SOLID_FOREGROUND
            val font = wb.createFont().apply { bold = true }
            setFont(font)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        fun data(fill: Short): CellStyle = wb.createCellStyle().apply {
            fillForegroundColor = fill
            fillPattern = FillPatternType.SOLID_FOREGROUND
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
            wrapText = false
        }

        // color choices: adjust if you like
        val mandColor = IndexedColors.LIGHT_CORNFLOWER_BLUE.index
        val reqColor = IndexedColors.GREY_25_PERCENT.index
        val expColor = IndexedColors.LIGHT_YELLOW.index
        val actColor = IndexedColors.LIGHT_GREEN.index

        return ColumnStyles(
            headerMandatory = header(mandColor),
            headerRequest = header(reqColor),
            headerExpected = header(expColor),
            headerActual = header(actColor),
            dataMandatory = data(IndexedColors.PALE_BLUE.index),
            dataRequest = data(IndexedColors.GREY_25_PERCENT.index),
            dataExpected = data(IndexedColors.LEMON_CHIFFON.index),
            dataActual = data(IndexedColors.LIGHT_TURQUOISE.index)
        )
    }

    /** parse response JSON safely (handles pretty JSON stored as a string) */
    private fun safeParseJson(maybeJson: String): JsonNode? =
        try {
            if (maybeJson.firstOrNull() == '{' || maybeJson.firstOrNull() == '[') {
                objectMapper.readTree(maybeJson)
            } else {
                val unquoted = if (maybeJson.startsWith("\"") && maybeJson.endsWith("\""))
                    objectMapper.readValue(maybeJson, String::class.java) else maybeJson
                objectMapper.readTree(unquoted)
            }
        } catch (_: Exception) {
            null
        }

    /** flatten JSON to dot-notation using your [0] convention */
    private fun flattenJson(node: JsonNode, path: String = ""): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        when {
            node.isObject -> node.fields().forEach { (k, v) ->
                val p = if (path.isEmpty()) k else "$path.$k"
                out.putAll(flattenJson(v, p))
            }

            node.isArray -> {
                if (node.size() > 0) out.putAll(flattenJson(node[0], "$path[0]"))
                else out[path] = null
            }

            else -> out[path] = when {
                node.isNull -> null
                node.isTextual || node.isNumber || node.isBoolean -> node.asText()
                else -> node.toString()
            }
        }
        return out
    }

    private fun buildZip(
        rows: List<RowBodyProjection>,
        filePrefix: String,
        defaultBody: String
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            rows.forEach { r ->
                // choose name: prefer testCaseId, else rowIndex
                val suffix = (r.testCaseId?.takeIf { it.isNotBlank() } ?: r.rowIndex.toString())
                val entryName = "${filePrefix}_${sanitizeFilename(suffix)}.json"
                val content = (r.body?.takeIf { it.isNotBlank() } ?: defaultBody)
                    .toByteArray(StandardCharsets.UTF_8)

                zos.putNextEntry(ZipEntry(entryName))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    suspend fun openArtifact(
        executionId: UUID,
        fileType: String
    ): Either<DomainError, ExecutionArtifact> = TODO()


    private fun sanitizeFilename(name: String): String =
        name.replace(Regex("""[^\w\-.]"""), "_")


    fun Raise<DomainError>.validatePagination(page: Int, size: Int) {
        ensure(page >= 0) { DomainError.Validation("Page must be >= 0") }
        ensure(size in 1..100) { DomainError.Validation("Size must be between 1 and 100") }
    }

    fun BulkExecution.toViewDto() = ExecutionHistoryItemDto(
        id = id!!,
        title = projectName,
        project = projectName,
        type = ExecutionType.BULK_EXECUTION,
        status = status,
        executedAt = createdAt,
        duration = formatDuration(createdAt, updatedAt),
        details = BulkDetails(
            rowCount = totalRows,
            successCount = successfulRows,
            failureCount = failedRows
        )
    )

    fun formatDuration(start: LocalDateTime?, end: LocalDateTime?): String {
        if (start == null || end == null) return "N/A"
        val duration = Duration.between(start, end)
        val totalSeconds = duration.seconds

        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m ")
            append("${seconds}s")
        }.trim()
    }


}

private enum class ColKind { MANDATORY, REQUEST, EXPECTED, ACTUAL }
