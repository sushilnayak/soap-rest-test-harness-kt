package com.nayak.app.security

import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class JwtAuthenticationFilter(private val jwtService: JwtService) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val token = extractJwtFromRequest(exchange.request) ?: return chain.filter(exchange)

        return try {
            if (!jwtService.validateToken(token)) {
                chain.filter(exchange)
            } else {
                val principal = jwtService.extractEmailFromToken(token)
                val authorities = jwtService.extractRolesFromToken(token)
                    .map { SimpleGrantedAuthority("ROLE_$it") }

                val auth = UsernamePasswordAuthenticationToken(principal, null, authorities)

                chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth))
            }
        } catch (_: Exception) {
            chain.filter(exchange) // If token parsing throws, continue unauthenticated
        }
    }

    private fun extractJwtFromRequest(httpRequest: ServerHttpRequest): String? {
        val bearerToken = httpRequest.headers.getFirst(HttpHeaders.AUTHORIZATION)

        return if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.removePrefix("Bearer ")
        } else null
    }

}