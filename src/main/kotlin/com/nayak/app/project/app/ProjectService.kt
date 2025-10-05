package com.nayak.app.project.app

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nayak.app.common.errors.DomainError
import com.nayak.app.project.model.Project
import com.nayak.app.project.model.ProjectFilter
import com.nayak.app.project.model.ProjectType
import com.nayak.app.project.repo.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.*

@Service
class ProjectService(private val projectRepository: ProjectRepository,  private val objectMapper: ObjectMapper) {

//    suspend fun findAll(): Either<DomainError, Flow<Project>> {
//        return try {
//            val projects = projectRepository.findAll()
//            projects.right()
//
//        } catch (e: Exception) {
//            DomainError.Database("Failed to fetch projects ${e.message}").left()
//        }
//    }

    suspend fun createProject(
        name: String,
        type: ProjectType,
        meta: JsonNode,
        requestTemplate: JsonNode? = null,
        responseTemplate: JsonNode? = null,
        ownerId: String
    ): Either<DomainError, ProjectDto> = either {

        ensure(!projectRepository.existsByNameExcludingId(name, null)) {
            raise(DomainError.Conflict("Project $name already exists"))
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

    suspend fun findByProjectId(id: UUID): Either<DomainError, Project> {
        return try {
            val project =
                projectRepository.findById(id) ?: return DomainError.NotFound("Project id not found with id $id").left()
            project.right()
        } catch (e: Exception) {
            DomainError.Database("Failed to find project with id $id").left()
        }
    }

    suspend fun findProjectById(projectId: UUID): Either<DomainError, Project> = either {

        val project = Either.catch {
            projectRepository.findById(projectId)
        }.mapLeft { _ -> raise(DomainError.Database("Failed to find project with id $projectId")) }.bind()
            ?: raise(DomainError.NotFound("Project id not found with id $projectId"))

        project

//        return try {
//            val project = projectRepository.findById(projectId)
//                ?: return DomainError.NotFound("Project id not found with id $projectId").left()
//            project.right()
//        } catch (e: Exception) {
//            DomainError.Database("Failed to find project with id $projectId").left()
//        }
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
                ?: raise(DomainError.NotFound("Project not found with id $projectId"))

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
            ?: raise(DomainError.NotFound("Project with id $id not found"))

        Either.catch {
            projectRepository.delete(project)
        }.mapLeft { DomainError.Database("Failed to delete project $id") }.bind()
        Unit
    }

    @Transactional(readOnly = true)
    suspend fun findAllPaginated(page: Int, size: Int) = either {
        validatePagination(
            page,
            size
        ) // don't need bind() as validatePagination now using Raise.
//        validatePagination(
//            page,
//            size
//        ).bind() // We need to use bind() otherwise either{} at top would not short circuit!

//        val offset = (page * size).toLong() // Avoid Int * Int then cast. - Do the multiplication in Long to prevent overflow: page.toLong() * size.
        val offset =
            page.toLong() * size.toLong() // Avoid Int * Int then cast. - Do the multiplication in Long to prevent overflow: page.toLong() * size.

        val total: Long = Either.catch { projectRepository.count() }
            .mapLeft { th -> DomainError.Database("Failed to count projects: ${th.message}") }
            .bind()

        val totalPages = ((total + size.toLong() - 1L) / size.toLong()).toInt()

        // Optional early exit: if page is beyond last page, return empty content
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
            projectRepository.findAllPaginated(size, offset).toList() // Flow<Project> -> List<Project>
        }
            .mapLeft { th -> DomainError.Database("Failed to fetch page $page: ${th.message}") }
            .bind()

        // 5) Map to DTOs and return
        val projectDtos = projects.map { it.toDto() }
//        val (project, total) = Either.catch {
//            val projectFlow = projectRepository.findAllPaginated(size, offset)
//            val projectList = projectFlow.toList()
//            val count = projectRepository.count()
//            projectList to count
//        }.mapLeft { e -> DomainError.Database("Failed to find page $offset: $e") }.bind()
//
//        val projectDtos = project.map { it.toDto() }
//        val totalPages = ((total + size - 1) / size).toInt()

        PagedResult(
            content = projectDtos,
            page = page,
            size = size,
            totalElements = total,
            totalPages = totalPages
        )
    }

    suspend fun searchProjects(
        name: String?,
        type: ProjectType?,
        ownerId: String?,
        filter: ProjectFilter,
        page: Int,
        size: Int
    ): Either<DomainError, PagedResult<ProjectDto>> =
        either {
            validatePagination(page, size)

            val offset = (page * size).toLong()
            val isActive: Boolean? = when (filter) {
                ProjectFilter.ACTIVE -> true
                ProjectFilter.INACTIVE -> false
                else -> null
            }

            val (projects, total) = Either.catch {
                val projectFlow = projectRepository.searchWithFilters(
                    name = name,
                    type = type?.name,
                    ownerId = ownerId,
                    isActive = isActive,
                    limit = size,
                    offset = offset
                )
                val projectList = projectFlow.toList()
                val count = projectRepository.countWithFilters(
                    name = name,
                    type = type?.name,
                    ownerId = ownerId,
                    isActive = isActive
                )
                projectList to count
            }.mapLeft { e ->
                DomainError.Database("Failed to search projects: ${e.message}")
            }.bind()

            val projectDtos = projects.map { it.toDto() }
            val totalPages = ((total + size - 1) / size).toInt()

            PagedResult(
                content = projectDtos,
                page = page,
                size = size,
                totalElements = total,
                totalPages = totalPages
            )
        }

    suspend fun autocompleteProjects(
        query: String,
        limit: Int = 10
    ): Either<DomainError, List<ProjectAutocompleteDto>> =
        either {
            ensure(query.isNotBlank()) {
                DomainError.Validation("Query cannot be blank")
            }

            ensure(limit in 1..50) {
                DomainError.Validation("Limit must be between 1 and 50")
            }

            val projectList = Either.catch {
                projectRepository.autocompleteByName(query.trim(), limit).map { it.toAutocompleteDto() }.toList()
            }.mapLeft { e ->
                DomainError.Database("Failed to autocomplete projects: ${e.message}")
            }

            projectList.bind()
        }

    //     fun validatePagination(page: Int, size: Int): Either<DomainError, Unit> =
//        either {
//            ensure(page >= 0) { DomainError.Validation("Page must be >= 0") }
//            ensure(size in 1..100) { DomainError.Validation("Size must be between 1 and 100") }
//        }

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

    fun Project.toAutocompleteDto() = ProjectAutocompleteDto(
        id = id!!,
        name = name,
        type = type
    )
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

data class ProjectAutocompleteDto(
    val id: UUID,
    val name: String,
    val type: ProjectType,
)

data class PagedResult<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

data class ProjectStats(
    val total: Long,
    val active: Long,
    val inactive: Long,
    val soapProjects: Long,
    val restProjects: Long
)