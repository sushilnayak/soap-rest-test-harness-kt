package com.nayak.app.config

import com.nayak.app.security.JwtAuthenticationFilter
import com.nayak.app.security.JwtService
import com.nayak.app.user.repo.UserRepository
import kotlinx.coroutines.reactor.mono
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.ReactiveAuthenticationManager
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(
    private val jwtService: JwtService,
    private val userRepository: UserRepository
) {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange { exchanges ->
                exchanges
                    .pathMatchers("/actuator/health").permitAll()
                    .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/webjars/**").permitAll()
                    .pathMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                    .anyExchange().authenticated()
            }
            .addFilterAfter(JwtAuthenticationFilter(jwtService), SecurityWebFiltersOrder.AUTHORIZATION)
            .build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun reactiveUserDetailsService(): ReactiveUserDetailsService {
        return ReactiveUserDetailsService { email ->
            mono {
                userRepository.findByEmail(email)?.let { user ->
                    User.builder()
                        .username(email)
                        .password(user.passwordHash)
                        .roles(*user.roles.toTypedArray())
                        .build()
                }
            }
        }
    }

    @Bean
    fun reactiveAuthenticationManager(
        userDetailsService: ReactiveUserDetailsService,
        passwordEncoder: PasswordEncoder
    ): ReactiveAuthenticationManager {
        return UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService).apply {
            setPasswordEncoder(passwordEncoder)
        }
    }
}