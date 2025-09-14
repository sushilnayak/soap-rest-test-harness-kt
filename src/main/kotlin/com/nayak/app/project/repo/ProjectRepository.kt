package com.nayak.app.project.repo

import com.nayak.app.project.model.Project
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface ProjectRepository: CoroutineCrudRepository<Project, UUID> {

}