package com.nayak.app.user.repo

import com.nayak.app.user.domain.User
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

interface UserRepository : CoroutineCrudRepository<User, UUID> {
    suspend fun findByRacfId(racfId: String): User?
    suspend fun existsByRacfId(racfId: String): Boolean
}