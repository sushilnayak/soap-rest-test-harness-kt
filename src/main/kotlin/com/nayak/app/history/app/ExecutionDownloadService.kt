package com.nayak.app.history.app

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.nayak.app.bulk.repo.BulkExecutionRepository
import com.nayak.app.common.errors.DomainError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Service
class ExecutionDownloadService(
    private val resultsRepo: BulkExecutionRepository
) {
    private val zipDateFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    suspend fun buildRequestsZipFileName(executionId: UUID): Either<DomainError, String> = either {
        val meta = resultsRepo.findById(executionId)
            ?: raise(DomainError.NotFound("No bulk execution found with id=$executionId"))
        "execution_${executionId}_${zipDateFmt.format(meta.createdAt)}_requests.zip"
    }

    suspend fun buildResponsesZipFileName(executionId: UUID): Either<DomainError, String> = either {
        val meta = resultsRepo.findById(executionId)
            ?: raise(DomainError.NotFound("No bulk execution found with id=$executionId"))
        "execution_${executionId}_${zipDateFmt.format(meta.createdAt)}_responses.zip"
    }

    /** Writes all request JSONs as ZIP entries to [out]. */
    suspend fun writeAllRequestsZip(executionId: UUID, out: OutputStream): Either<DomainError, Unit> = either {
        ensure(resultsRepo.existsById(executionId)) {
            DomainError.NotFound("No bulk execution found with id=$executionId")
        }
        // Do all blocking I/O in IO dispatcher.
        withContext(Dispatchers.IO) {
            ZipOutputStream(out).use { zos ->
                resultsRepo.streamAllRequests(executionId).collect { row ->
                    val name = "request_${(row.testCaseId?.takeIf { it.isNotBlank() } ?: row.rowIndex.toString())}.json"
                    println(name)

                    val bytes = (row.body?.ifBlank { "{}" } ?: "{}").toByteArray(StandardCharsets.UTF_8)
                    zos.putNextEntry(ZipEntry(sanitize(name)))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
    }

    suspend fun writeAllResponsesZip(executionId: UUID, out: OutputStream): Either<DomainError, Unit> = either {
        ensure(resultsRepo.existsById(executionId)) {
            DomainError.NotFound("No bulk execution found with id=$executionId")
        }
        withContext(Dispatchers.IO) {
            ZipOutputStream(out).use { zos ->
                resultsRepo.streamAllResponses(executionId).collect { row ->
                    val name =
                        "response_${(row.testCaseId?.takeIf { it.isNotBlank() } ?: row.rowIndex.toString())}.json"
                    println(name)
                    val bytes = (row.body?.ifBlank { "{}" } ?: "{}").toByteArray(StandardCharsets.UTF_8)
                    zos.putNextEntry(ZipEntry(sanitize(name)))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("""[^\w\-.]"""), "_")
}
