package com.nayak.app.project.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.databind.JsonNode
import com.nayak.app.common.errors.DomainError
import com.nayak.app.project.model.Project
import com.nayak.app.project.model.ProjectType
import com.nayak.app.project.repo.ProjectRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.stereotype.Service
import java.util.*

@Service
class ProjectService(val projectRepository: ProjectRepository) {

    suspend fun findAll(): Either<DomainError, Flow<Project>> {
        return try {
            val projects = projectRepository.findAll()
            projects.right()

        } catch (e: Exception) {
            DomainError.Database("Failed to fetch projects ${e.message}").left()
        }
    }

    suspend fun createProject(
        name: String,
        type: ProjectType,
        meta: JsonNode,
        requestTemplate: JsonNode? = null,
        responseTemplate: JsonNode? = null,
        ownerId: String
    ): Either<DomainError, Project> {
        return try {
            val project = Project(
                name = name,
                type = type,
                meta = meta,
                requestTemplate = requestTemplate,
                responseTemplate = responseTemplate,
                ownerId = ownerId
            )
            val savedProject = projectRepository.save(project)
            savedProject.right()
        } catch (e: Exception) {
            DomainError.Database("Failed to create project: ${e.message}").left()
        }
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

    suspend fun findProjectById(projectId: UUID): Either<DomainError, Project> {
        return try {
            val project = projectRepository.findById(projectId)
                ?: return DomainError.NotFound("Project id not found with id $projectId").left()
            project.right()
        } catch (e: Exception) {
            DomainError.Database("Failed to find project with id $projectId").left()
        }
    }
}