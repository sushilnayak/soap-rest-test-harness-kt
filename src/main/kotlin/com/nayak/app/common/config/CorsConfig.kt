//package com.nayak.app.common.config
//
//
//import org.springframework.context.annotation.Bean
//import org.springframework.context.annotation.Configuration
//import org.springframework.core.Ordered
//import org.springframework.core.annotation.Order
//import org.springframework.http.HttpHeaders
//import org.springframework.http.HttpMethod
//import org.springframework.web.cors.CorsConfiguration
//import org.springframework.web.cors.reactive.CorsWebFilter
//import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
//
//@Configuration
//class CorsConfig {
//
//    @Bean
//    @Order(-100) // Run before Spring Security
//    fun corsWebFilter(): CorsWebFilter {
//        val corsConfiguration = CorsConfiguration().apply {
//
//            // Allow requests from your React dev server
//            allowedOrigins = listOf(
//                "http://localhost:5173",  // Vite default port
//                "http://localhost:3000",  // Create React App default port
//                "http://localhost:5174",  // Alternative Vite port
//                "http://localhost:4173"   // Vite preview port
//            )
//
//            // Allow all HTTP methods
//            allowedMethods = listOf(
//                HttpMethod.GET.name(),
//                HttpMethod.POST.name(),
//                HttpMethod.PUT.name(),
//                HttpMethod.PATCH.name(),
//                HttpMethod.DELETE.name(),
//                HttpMethod.OPTIONS.name()
//            )
//
//            // Allow all headers
//            allowedHeaders = listOf(
//                HttpHeaders.AUTHORIZATION,
//                HttpHeaders.CONTENT_TYPE,
//                HttpHeaders.ACCEPT,
//                "X-Requested-With",
//                "X-XSRF-TOKEN"
//            )
//
//            // Expose headers that client can read
//            exposedHeaders = listOf(
//                HttpHeaders.AUTHORIZATION,
//                HttpHeaders.CONTENT_DISPOSITION,
//                "X-Total-Count"
//            )
//
//            // Allow credentials (cookies, authorization headers)
//            allowCredentials = true
//
//            // Cache preflight response for 1 hour
//            maxAge = 3600L
//        }
//
//        val source = UrlBasedCorsConfigurationSource().apply {
//            registerCorsConfiguration("/**", corsConfiguration)
//        }
//
//        return CorsWebFilter(source)
//    }
//}