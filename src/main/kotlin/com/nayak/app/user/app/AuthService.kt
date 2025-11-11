package com.nayak.app.user.app

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.nayak.app.common.errors.DomainError
import com.nayak.app.common.support.db
import com.nayak.app.security.JwtService
import com.nayak.app.user.domain.User
import com.nayak.app.user.repo.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService
) {

    suspend fun signup(racfId: String, password: String): Either<DomainError, TokenResponse> = either {

        ensure(!userRepository.existsByRacfId(racfId)) {
            DomainError.Conflict("User with racfId '$racfId' already exists")
        }

        val hash = Either.catch { passwordEncoder.encode(password) }
            .mapLeft { e -> DomainError.Authentication("Failed to encode password: ${e.message}") }
            .bind()

        val user = User(racfId = racfId, passwordHash = hash, roles = setOf("USER"))

        val updatedUser = Either.catch { userRepository.save(user) }
            .mapLeft { e ->
                DomainError.Database("Failed to create user: ${e.message}")
            }
            .bind()

        Either.catch {
            val token = jwtService.generateToken(updatedUser.racfId, updatedUser.roles)
            val expiresIn = jwtService.getExpirationInMinutes() * 60

            TokenResponse(
                accessToken = token,
                expiresIn = expiresIn,
                tokenType = "Bearer",
            )
        }.mapLeft { e -> DomainError.Authentication("Token generation failed : ${e.message}") }.bind()

    }

    suspend fun login(racfId: String, password: String): Either<DomainError, TokenResponse> = either {

        val user = Either.catch {
            userRepository.findByRacfId(racfId) ?: raise(DomainError.NotFound("User not found with $racfId"))
        }.mapLeft { th -> DomainError.Database("User lookup failed: ${th.message}") }.bind()

        ensure(passwordEncoder.matches(password, user.passwordHash)) {
            raise(DomainError.Authentication("Invalid credential"))
        }

        Either.catch {
            val token = jwtService.generateToken(user.racfId, user.roles)
            val expiresIn = jwtService.getExpirationInMinutes() * 60

            TokenResponse(
                accessToken = token,
                expiresIn = expiresIn,
                tokenType = "Bearer",
            )
        }.mapLeft { e -> DomainError.Authentication("Authentication failed : ${e.message}") }.bind()

    }

    suspend fun me(racfId: String?): Either<DomainError, UserDto> = either {

        ensure(!racfId.isNullOrBlank()) {
            raise(DomainError.Validation("Invalid RacfId"))
        }

        val user = db("Fetching $racfId failed") { userRepository.findByRacfId(racfId) }.bind()
        ensureNotNull(user) { DomainError.NotFound("User not found - ${racfId}") }

        user.toDto()
    }
}

data class TokenResponse(
    val accessToken: String,
    val expiresIn: Long,
    val tokenType: String,
)

fun User.toDto() = UserDto(
    id = id!!,
    racfId = racfId,
    roles = roles,
    isEnabled = isEnabled
)