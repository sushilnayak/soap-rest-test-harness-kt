package com.nayak.app.user.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.nayak.app.common.errors.DomainError
import com.nayak.app.security.JwtService
import com.nayak.app.user.domain.User
import com.nayak.app.user.repo.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService
) {

    suspend fun signup(racfId: String, password: String): Either<DomainError, User> =
        try {
            if (userRepository.existsByRacfId(racfId)) {
                DomainError.Conflict("User with racfId '$racfId' already exists").left()
            } else {
                val passwordHash = passwordEncoder.encode(password)
                val user = User(
                    racfId = racfId,
                    passwordHash = passwordHash,
                    roles = setOf("USER")
                )
                val savedUser = userRepository.save(user)
                savedUser.right()
            }
        } catch (e: Exception) {
            DomainError.Database("Failed to create user: ${e.message}").left()
        }

    suspend fun login(racfId: String, password: String): Either<DomainError, TokenResponse> {
        return try {
            val user =
                userRepository.findByRacfId(racfId) ?: return DomainError.NotFound("User not found with $racfId").left()

            if (passwordEncoder.matches(password, user.passwordHash)) {
                val token = jwtService.generateToken(user.racfId, user.roles)
                val expiresIn = jwtService.getExpirationInMinutes() * 60 // in seconds

                TokenResponse(
                    accessToken = token,
                    expiresIn = expiresIn,
                    tokenType = "Bearer"
                ).right()

            } else DomainError.Validation("Invalid credential").left()

        } catch (e: Exception) {
            DomainError.Database("Authentication failed : ${e.message}").left()
        }
    }
}

data class TokenResponse(
    val accessToken: String,
    val expiresIn: Long,
    val tokenType: String,
)