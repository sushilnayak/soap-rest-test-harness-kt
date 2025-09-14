package com.nayak.app.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("SOAP/JSON Test Harness")
                    .description("SOAP/JSON Test Harness - API testing and automation platform")
                    .version("v1.0.0")
            )
            .components(
                Components().addSecuritySchemes(
                    "bearer",
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .`in`(SecurityScheme.In.HEADER)
                        .name("Authorization")
                )
            )
    }
}