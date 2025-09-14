package com.nayak.app.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${app.jwt.secret}") private val secret: String,
    @Value("\${app.jwt.expMinutes}") private val expirationMinutes: Long
) {

    private val secretKey: SecretKey by lazy {
        Keys.hmacShaKeyFor(secret.toByteArray())
    }

    fun generateToken(email: String, roles: Set<String>): String {
        val now = Instant.now()
        val expiryDate = now.plusSeconds(expirationMinutes * 60)

        return Jwts.builder()
            .subject(email)
            .claim("roles", roles.joinToString(","))
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiryDate))
            .signWith(secretKey)
            .compact()
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun extractEmailFromToken(token: String): String? {
        return try {
            val claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).payload
            claims.subject
        } catch (e: Exception) {
            null
        }
    }

    fun extractRolesFromToken(token: String): Set<String> {
        return try {
            val claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).payload
            val rolesString = claims.get("claims", String::class.java)

            rolesString?.split(",")?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun getExpirationInMinutes(): Long = expirationMinutes
}