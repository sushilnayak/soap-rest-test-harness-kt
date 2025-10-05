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
        WHERE (:racfId IS NULL OR racf_id ILIKE CONCAT('%', :racfId, '%'))
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
    """
    )
    fun searchByRacfId(racfId: String?, limit: Int, offset: Long): Flow<User>

    @Query(
        """
        SELECT COUNT(*) FROM users  
        AND (:racfId IS NULL OR racf_id ILIKE CONCAT('%', :racfId, '%'))
    """
    )
    suspend fun countByRacfId(racfId: String?): Long

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