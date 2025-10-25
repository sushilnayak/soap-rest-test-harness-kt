package com.nayak.app.project.repo

import com.nayak.app.project.app.ProjectWithNameAndId
import com.nayak.app.project.model.Project
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

interface ProjectRepository : CoroutineCrudRepository<Project, UUID> {


    @Query(
        """
        SELECT * FROM projects
          WHERE 1=1
          AND (:type IS NULL OR type = :type)
          AND (:search IS NULL OR (
             name ILIKE CONCAT('%', :search, '%') 
             OR meta->>'targetUrl' ILIKE CONCAT('%', :search, '%')
      ))
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
    """
    )
    fun findAllPaginated(type: String?, search: String?, limit: Int, offset: Long): Flow<Project>

    @Query(
        """
    SELECT id, name FROM projects
    ORDER BY created_at DESC
    """
    )
    fun findAllProjectNameIds(): Flow<ProjectWithNameAndId>

    // Search by name with pagination
    @Query(
        """
        SELECT * FROM projects  
        WHERE name ILIKE CONCAT('%', :name, '%')
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
    """
    )
    fun searchByName(name: String, limit: Int, offset: Long): Flow<Project>

//    @Query(
//        """
//        SELECT COUNT(*) FROM projects
//        WHERE name ILIKE CONCAT('%', :name, '%')
//    """
//    )
//    suspend fun countByName(name: String): Long

    @Query(
        """
        SELECT COUNT(*) FROM projects  
        WHERE 1=1
          AND (:type IS NULL OR type = :type)
          AND (:search IS NULL OR ( name ILIKE CONCAT('%', :search, '%') OR meta->>'targetUrl' ILIKE CONCAT('%', :search, '%') ) )
    """
    )
    suspend fun countByTypeAndName(type: String?, search: String?): Long

    // Autocomplete - returns limited results sorted by relevance
    @Query(
        """
        SELECT * FROM projects  
        WHERE name ILIKE CONCAT('%', :query, '%')
        ORDER BY 
            CASE WHEN name ILIKE CONCAT(:query, '%') THEN 0 ELSE 1 END,
            LENGTH(name),
            name
        LIMIT :limit
    """
    )
    fun autocompleteByName(query: String, limit: Int = 10): Flow<Project>

    // Search by owner with name filter
//    @Query(
//        """
//        SELECT * FROM projects
//        WHERE owner_id = :ownerId
//        AND (:name IS NULL OR name ILIKE CONCAT('%', :name, '%'))
//        ORDER BY created_at DESC
//        LIMIT :limit OFFSET :offset
//    """
//    )
//    fun searchByOwnerAndName(ownerId: String, name: String?, limit: Int, offset: Long): Flow<Project>

//    @Query(
//        """
//        SELECT COUNT(*) FROM projects
//        AND owner_id = :ownerId
//        AND (:name IS NULL OR name ILIKE CONCAT('%', :name, '%'))
//    """
//    )
//    suspend fun countByOwnerAndName(ownerId: String, name: String?): Long
//
//    // Filter by type
//    @Query(
//        """
//        SELECT * FROM projects
//        WHERE type = :type
//        AND (:name IS NULL OR name ILIKE CONCAT('%', :name, '%'))
//        ORDER BY created_at DESC
//        LIMIT :limit OFFSET :offset
//    """
//    )
//    fun searchByTypeAndName(type: String, name: String?, limit: Int, offset: Long): Flow<Project>
//
////    @Query(
////        """
////        SELECT COUNT(*) FROM projects
////        WHERE type = :type
////        AND (:name IS NULL OR name ILIKE CONCAT('%', :name, '%'))
////    """
////    )
////    suspend fun countByTypeAndName(type: String, name: String?): Long
//
//    // Filter by active status
//    @Query(
//        """
//        SELECT * FROM projects
//        WHERE is_active = :isActive
//        AND (:name IS NULL OR name ILIKE CONCAT('%', :name, '%'))
//        ORDER BY created_at DESC
//        LIMIT :limit OFFSET :offset
//    """
//    )
//    fun searchByActiveStatusAndName(isActive: Boolean, name: String?, limit: Int, offset: Long): Flow<Project>
//
//    @Query(
//        """
//        SELECT COUNT(*) FROM projects
//        WHERE is_active = :isActive
//        AND (:name IS NULL OR name ILIKE CONCAT('%', :name, '%'))
//    """
//    )
//    suspend fun countByActiveStatusAndName(isActive: Boolean, name: String?): Long

    // Complex search with multiple filters
    @Query(
        """
        SELECT * FROM projects  
        WHERE (:name IS NULL OR name ILIKE CONCAT('%', :name, '%'))
        AND (:type IS NULL OR type = :type)
        AND (:ownerId IS NULL OR owner_id = :ownerId)
        AND (:isActive IS NULL OR is_active = :isActive)
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
    """
    )
    fun searchWithFilters(
        name: String?,
        type: String?,
        ownerId: String?,
        isActive: Boolean?,
        limit: Int,
        offset: Long
    ): Flow<Project>

    @Query(
        """
        SELECT COUNT(*) FROM projects  
        WHERE (:name IS NULL OR name ILIKE CONCAT('%', :name, '%'))
        AND (:type IS NULL OR type = :type)
        AND (:ownerId IS NULL OR owner_id = :ownerId)
        AND (:isActive IS NULL OR is_active = :isActive)
    """
    )
    suspend fun countWithFilters(
        name: String?,
        type: String?,
        ownerId: String?,
        isActive: Boolean?
    ): Long

    // Check if name exists for uniqueness validation
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM projects   
            WHERE name = :name
            AND (:excludeId IS NULL OR id != :excludeId)
        )
    """
    )
    suspend fun existsByNameExcludingId(name: String, excludeId: UUID?): Boolean
}