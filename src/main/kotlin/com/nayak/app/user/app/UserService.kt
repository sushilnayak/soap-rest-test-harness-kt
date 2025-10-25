package com.nayak.app.user.app

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.nayak.app.common.errors.DomainError
import com.nayak.app.user.domain.RoleFilter
import com.nayak.app.user.domain.User
import com.nayak.app.user.domain.UserRole
import com.nayak.app.user.repo.UserRepository
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.util.*

@Service
class UserService(
    private val userRepository: UserRepository
) {

    suspend fun deleteUser(racfId: String): Either<DomainError, Unit> = either {
        val user = userRepository.findByRacfId(racfId)
            ?: raise(DomainError.NotFound("User with racfId '$racfId' does not exist"))

        Either.catch {
            userRepository.delete(user)
        }.mapLeft { e ->
            DomainError.Database("Failed to delete user: ${e.message}")
        }.bind()
        Unit
    }

    suspend fun enableUser(racfId: String): Either<DomainError, Unit> = either {
        val user = userRepository.findByRacfId(racfId)
            ?: raise(DomainError.NotFound("User with racfId '$racfId' does not exist"))

        ensure(!user.isEnabled) {
            DomainError.Database("User is already disabled")
        }

        Either.catch {
            userRepository.save(user.enable())
        }.mapLeft { e ->
            DomainError.Database("Failed to enable user: ${e.message}")
        }.bind()
    }

    suspend fun disableUser(racfId: String): Either<DomainError, Unit> = either {
        val user = userRepository.findByRacfId(racfId)
            ?: raise(DomainError.NotFound("User with racfId '$racfId' does not exist"))

        ensure(user.isEnabled) { DomainError.Conflict("User is already disabled") }

        Either.catch { userRepository.save(user.disable()) }
            .mapLeft { e -> DomainError.Database("Failed to disable user: ${e.message}") }
            .bind()
    }


    suspend fun updateUserRole(
        racfId: String,
        role: String,
        operation: RoleOperation
    ): Either<DomainError, User> = either {

        val user = Either.catch { userRepository.findByRacfId(racfId) }
            .mapLeft { th -> DomainError.Database("User lookup failed: ${th.message}") }
            .bind()
            ?: raise(DomainError.NotFound("User not found with racfId $racfId"))

        val normalized = role.trim()
        val validRole = UserRole.fromString(normalized) /* implement equalsIgnoreCase inside */
            ?: raise(
                DomainError.Validation(
                    "Invalid role: $normalized. Valid roles: ${UserRole.entries.joinToString { it.name }}"
                )
            )

        val hasRole = validRole.name in user.roles
        val isUserRole = validRole == UserRole.USER
        val isOnlyUserRole = isUserRole && user.roles.size == 1

        val updatedUser = when (operation) {
            RoleOperation.ADD -> {
                ensure(!hasRole) {
                    DomainError.Conflict("User already has role ${validRole.name}")
                }
                user.addRole(validRole.name)
            }

            RoleOperation.REMOVE -> {
                ensure(hasRole) {
                    DomainError.Conflict("User does not have role ${validRole.name}")
                }
                ensure(!isOnlyUserRole) {
                    DomainError.Conflict("Cannot remove USER role when it's the only role")
                }
                user.removeRole(validRole.name)
            }
        }

        Either.catch { userRepository.save(updatedUser) }
            .mapLeft { th -> DomainError.Database("Failed to update user role: ${th.message}") }
            .bind()
    }

    suspend fun searchUsers(
        racfId: String?,
        roleFilter: RoleFilter,
        page: Int,
        size: Int
    ): Either<DomainError, PagedResult<UserDto>> =
        either {
            ensure(page >= 0) { DomainError.Validation("Page must be >= 0") }
            ensure(size in 1..100) { DomainError.Validation("Size must be between 1 and 100") }
            val offset = (page * size).toLong()

            val (users, total) = Either.catch {
                when (roleFilter) {
                    RoleFilter.ALL -> {
                        val userFlow = userRepository.searchByRacfId(size, offset)
                        val userList = userFlow.toList()
                        val count = userRepository.countByRacfId()
                        userList to count
                    }

                    RoleFilter.USER -> {
                        val userFlow = userRepository.findByRole("USER", size, offset)
                        val filtered = if (racfId != null) {
                            userFlow.filter { it.racfId.contains(racfId, ignoreCase = true) }.toList()
                        } else {
                            userFlow.toList()
                        }
                        val count = userRepository.countByRole("USER")
                        filtered to count
                    }

                    RoleFilter.ADMIN -> {
                        val userFlow = userRepository.findByRole("ADMIN", size, offset)
                        val filtered = if (racfId != null) {
                            userFlow.filter { it.racfId.contains(racfId, ignoreCase = true) }.toList()
                        } else {
                            userFlow.toList()
                        }
                        val count = userRepository.countByRole("ADMIN")
                        filtered to count
                    }
                }
            }.mapLeft { e ->
                DomainError.Database("Failed to search users: ${e.message}")
            }.bind()

            val userDtos = users.map { it.toDto() }
            val totalPages = ((total + size - 1) / size).toInt()

            PagedResult(
                content = userDtos,
                page = page,
                size = size,
                totalElements = total,
                totalPages = totalPages
            )
        }

    suspend fun getUserByRacfId(userId: String): Either<DomainError, UserDto> =
        either {
            val user = userRepository.findByRacfId(userId)
                ?: raise(DomainError.NotFound("User not found with Racf ID $userId"))

            user.toDto()
        }

    private fun User.toDto() = UserDto(
        id = id!!,
        racfId = racfId,
        roles = roles,
        isEnabled = isEnabled
    )
}

enum class RoleOperation {
    ADD, REMOVE
}


data class UserDto(
    val id: UUID,
    val racfId: String,
    val roles: Set<String>,
    val isEnabled: Boolean
)

data class PagedResult<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)