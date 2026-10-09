package com.pitsch.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of /api/v1 (served at /api/v1/openapi, Swagger UI at /api/docs). Disabled unless
 * API_DOCS_ENABLED=true; the endpoints are public paths, so enable it only where that is intended.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pitschOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Pitsch API").version("v1")
                        .description("Multi-tenant pitch management and AI investment workflow API. All endpoints except "
                                + "/auth/*, invitations lookup and webhooks require a Bearer access token; the workspace is "
                                + "taken from the session, never from the request. Errors use {code, message, requestId, details}."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
