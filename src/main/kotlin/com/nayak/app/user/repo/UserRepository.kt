package com.nayak.app.user.repo

import com.nayak.app.user.domain.User
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface UserRepository : CoroutineCrudRepository<User, UUID> {
    suspend fun findByEmail(email: String): User?
    suspend fun existsByEmail(email: String): Boolean
}