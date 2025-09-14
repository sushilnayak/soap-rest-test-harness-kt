package com.nayak.app.user.api

import com.nayak.app.common.errors.toHttpStatus
import com.nayak.app.common.http.ApiResponse
import com.nayak.app.user.app.UserService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "User Authentication Endpoint")
class UserController(private val userService: UserService) {

    @PostMapping("/signup")
    suspend fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<ApiResponse<Unit>> {
        return userService.signup(request.email, request.password).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Unit>(error.message))
            },
            ifRight = {
                ResponseEntity.ok(ApiResponse.success(Unit, "User Registered Successfully"))
            }
        )
    }

    @PostMapping("/login")
    @Operation(summary = "Log in", description = "Authenticate user and get JWT Token", tags = ["Authentication"])
    suspend fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<ApiResponse<Any>> {
        return userService.login(request.email, request.password).fold(
            ifLeft = { error ->
                ResponseEntity.status(error.toHttpStatus()).body(ApiResponse.error<Any>(error.message))
            },
            ifRight = { tokenResponse -> ResponseEntity.ok(ApiResponse.success(tokenResponse, "Login Successful")) }
        )
    }
}

data class SignupRequest(
    @field:NotBlank(message = "Email cannot be blank")
    val email: String,
    @field:NotBlank

    @field:NotBlank(message = "Password cannot be blank")
    val password: String
)

data class LoginRequest(
    @field:NotBlank(message = "Email cannot be blank")
    val email: String,

    @field:NotBlank(message = "Password cannot be blank")
    val password: String
)