package com.nayak.app.project.app

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.common.errors.DomainError
import com.nayak.app.project.model.Project
import com.nayak.app.project.model.ProjectType
import com.nayak.app.project.repo.ProjectRepository
import kotlinx.coroutines.flow.toList
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.*

@Service
class ProjectService(private val projectRepository: ProjectRepository, private val objectMapper: ObjectMapper) {


    suspend fun createProject(
        name: String,
        type: ProjectType,
        meta: JsonNode,
        requestTemplate: JsonNode? = null,
        responseTemplate: JsonNode? = null,
        ownerId: String
    ): Either<DomainError, ProjectDto> = either {

        ensure(!projectRepository.existsByNameExcludingId(name, null)) {
            DomainError.Conflict("Project $name already exists")
        }

        val project = Project(
            name = name,
            type = type,
            meta = meta,
            requestTemplate = requestTemplate,
            responseTemplate = responseTemplate,
            ownerId = ownerId
        )

        Either.catch {
            projectRepository.save(project)
        }.mapLeft { e ->
            DomainError.Database("Failed to create project: ${e.message}")
        }.bind().toDto()
    }


    suspend fun findProjectById(projectId: UUID): Either<DomainError, Project> = either {

        val project = Either.catch {
            projectRepository.findById(projectId)
        }.mapLeft { _ -> DomainError.Database("Failed to find project with id $projectId") }.bind()

        ensureNotNull(project) { DomainError.NotFound("Project id not found with id $projectId") }

        project
    }

    suspend fun updateProject(
        projectId: UUID,
        name: String?,
        meta: JsonNode?,
        requestTemplate: JsonNode?,
        responseTemplate: JsonNode?,
        updaterId: String
    ): Either<DomainError, ProjectDto> =
        either {
            val project = Either.catch { projectRepository.findById(projectId) }
                .mapLeft { th -> DomainError.Database("Project lookup failed: ${th.message}") }
                .bind()

            ensureNotNull(project) { DomainError.NotFound("Project with id $projectId") }

            // Validate unique name if changing
            val normalizedName = name?.trim()
            if (normalizedName != null && normalizedName != project.name) {
                ensure(normalizedName.isNotEmpty()) {
                    DomainError.Validation("Project name must not be blank")
                }

                val exists = Either.catch { projectRepository.existsByNameExcludingId(normalizedName, projectId) }
                    .mapLeft { th -> DomainError.Database("Name uniqueness check failed: ${th.message}") }
                    .bind()

                ensure(!exists) {
                    DomainError.Conflict("Project with name '$normalizedName' already exists")
                }
            }

            val updatedProject = project.copy(
                name = name ?: project.name,
                meta = meta?.deepCopy() ?: project.meta,
                requestTemplate = requestTemplate?.deepCopy() ?: project.requestTemplate,
                responseTemplate = responseTemplate?.deepCopy() ?: project.responseTemplate,
                updatedAt = LocalDateTime.now()
            )

            val noPayloadChange =
                (normalizedName == null || normalizedName == project.name) &&
                        meta == null && requestTemplate == null && responseTemplate == null
            if (noPayloadChange) return@either project.toDto()

            val saved = Either.catch { projectRepository.save(updatedProject) }
                .mapLeft { th ->
                    when (th) {
                        is DataIntegrityViolationException ->
                            DomainError.Conflict("Project with name '${updatedProject.name}' already exists")

                        else ->
                            DomainError.Database("Failed to update project $projectId: ${th.message}")
                    }
                }
                .bind()

            saved.toDto()
        }

    @Transactional
    suspend fun deleteProject(id: UUID): Either<DomainError, Unit> = either {
        val project = Either.catch {
            projectRepository.findById(id)
        }.mapLeft { DomainError.Database("Failed to find project with id=$id") }.bind()

        ensureNotNull(project) { DomainError.NotFound("Project with id $id") }

        Either.catch {
            projectRepository.delete(project)
        }.mapLeft { DomainError.Database("Failed to delete project $id") }.bind()

        Unit
    }

    @Transactional(readOnly = true)
    suspend fun findAllPaginated(type: ProjectType?, search: String?, page: Int, size: Int) = either {
        validatePagination(
            page,
            size
        )

        val offset = page.toLong() * size.toLong()

        val total: Long = Either.catch { projectRepository.countByTypeAndName(type = type?.name, search = search) }
            .mapLeft { th -> DomainError.Database("Failed to count projects: ${th.message}") }
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
        val projects: List<Project> = Either.catch {
            projectRepository.findAllPaginated(type = type?.name, search = search, limit = size, offset = offset)
                .toList() // Flow<Project> -> List<Project>
        }
            .mapLeft { th -> DomainError.Database("Failed to fetch page $page: ${th.message}") }
            .bind()

        val projectDtos = projects.map { it.toViewDto() }

        PagedResult(
            content = projectDtos,
            page = page,
            size = size,
            totalElements = total,
            totalPages = totalPages
        )
    }


    fun Raise<DomainError>.validatePagination(page: Int, size: Int) {
        ensure(page >= 0) { DomainError.Validation("Page must be >= 0") }
        ensure(size in 1..100) { DomainError.Validation("Size must be between 1 and 100") }
    }


    fun Project.toDto() = ProjectDto(
        id = id!!,
        name = name,
        type = type,
        meta = meta,
        requestTemplate = requestTemplate,
        responseTemplate = responseTemplate,
        ownerId = ownerId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    fun Project.toViewDto() = ProjectViewDto(
        id = id!!,
        name = name,
        targetUrl = meta.path("targetUrl").asText(null),
        type = type,
        ownerId = ownerId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    fun Project.toAutocompleteDto() = ProjectAutocompleteDto(
        id = id!!,
        name = name,
        type = type
    )

    suspend fun findProjectWithNameIds() = either {
        Either.catch {
            projectRepository.findAllProjectNameIds().toList()
        }.mapLeft { th -> DomainError.Database("Failed to fetch") }.bind()
    }
}

// DTOs
data class ProjectDto(
    val id: UUID,
    val name: String,
    val type: ProjectType,
    val meta: JsonNode,
    val requestTemplate: JsonNode?,
    val responseTemplate: JsonNode?,
    val ownerId: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class ProjectViewDto(
    val id: UUID,
    val name: String,
    val targetUrl: String,
    val type: ProjectType,
    val ownerId: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class ProjectAutocompleteDto(
    val id: UUID,
    val name: String,
    val type: ProjectType,
)

data class ProjectWithNameAndId(
    val id: UUID,
    val name: String
)

data class PagedResult<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)