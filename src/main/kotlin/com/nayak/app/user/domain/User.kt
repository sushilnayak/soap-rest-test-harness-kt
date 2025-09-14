package com.nayak.app.user.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDateTime
import java.util.UUID

@Table("users")
data class User(
    @Id
    val id: UUID? = null,
    @Column("email")
    val email: String,
    @Column("password_hash")
    val passwordHash: String,

    val roles: Set<String> = setOf("USER"),
    @Column("created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column("updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now(),
)
