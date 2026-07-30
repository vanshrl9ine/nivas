package com.nivas.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI nivasOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Nivas API")
                        .version("v1")
                        .description("""
                                Property search platform.

                                Postgres is the system of record. Elasticsearch serves search and is
                                kept in step through a transactional outbox, so it can always be
                                rebuilt from the database. Redis handles caching, rate limiting and
                                view counters.

                                Authenticate by sending `Authorization: Bearer <accessToken>` using a
                                token from `/api/v1/auth/login`.""")
                        .license(new License().name("MIT")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                // Applied globally so the "Authorize" button works everywhere;
                // genuinely public endpoints simply ignore the header.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
