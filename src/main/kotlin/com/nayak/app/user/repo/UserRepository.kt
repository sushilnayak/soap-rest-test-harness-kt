package com.nayak.app.user.repo

import com.nayak.app.user.domain.User
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

interface UserRepository : CoroutineCrudRepository<User, UUID> {

    suspend fun findByRacfId(racfId: String): User?

    suspend fun existsByRacfId(racfId: String): Boolean

    @Query(
        """
        SELECT * FROM users  
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
    """
    )
    fun searchByRacfId(limit: Int, offset: Long): Flow<User>
//        WHERE (:racfId IS NULL OR racf_id ILIKE CONCAT('%', :racfId, '%'))

    @Query(
        """
        SELECT COUNT(*) FROM users  
    """
    )
    suspend fun countByRacfId(): Long
//        AND (:racfId IS NULL OR racf_id ILIKE CONCAT('%', :racfId, '%'))

    @Query(
        """
        SELECT * FROM users 
        WHERE is_deleted = false 
        AND roles @> ARRAY[:role]::text[]
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
    """
    )
    fun findByRole(role: String, limit: Int, offset: Long): Flow<User>

    @Query(
        """
        SELECT COUNT(*) FROM users 
        WHERE is_deleted = false 
        AND roles @> ARRAY[:role]::text[]
    """
    )
    suspend fun countByRole(role: String): Long

}