package com.nayak.app.user.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime
import java.util.*

@Table("users")
data class User(
    @Id
    val id: UUID? = null,
    @Column("racf_id")
    val racfId: String,
    @Column("password_hash")
    val passwordHash: String,

    val roles: Set<String> = setOf("USER"),
    @Column("created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column("updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),

    @Column("is_enabled")
    val isEnabled: Boolean = false,

    ) {
    fun isAdmin(): Boolean = roles.contains("ADMIN")

    fun enable(): User = copy(isEnabled = true, updatedAt = LocalDateTime.now())

    fun disable(): User = copy(isEnabled = false, updatedAt = LocalDateTime.now())

    fun addRole(role: String): User = copy(
        roles = roles + role.uppercase(),
        isEnabled = false,
        updatedAt = LocalDateTime.now()
    )

    fun removeRole(role: String): User = copy(
        roles = roles - role.uppercase(),
        updatedAt = LocalDateTime.now()
    )
}

enum class UserRole {
    ADMIN, USER;

    companion object {
        fun fromString(role: String): UserRole? = entries.find { it.name.equals(role, ignoreCase = true) }
    }
}

enum class RoleFilter {
    ALL, USER, ADMIN
}