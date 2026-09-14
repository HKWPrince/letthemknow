package io.letthemknow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI at {@code /api/docs}, spec at {@code /api/docs/openapi}. */
@Configuration
class OpenApiConfig {

    /**
     * The spec a tenant's own developers read. Only the three X-API-KEY endpoints, so nobody has to work
     * out which of thirty paths applies to them, and the console's admin surface is not advertised.
     */
    @Bean
    GroupedOpenApi integrationApi() {
        return GroupedOpenApi.builder()
                .group("integration")
                .displayName("Integration API (X-API-KEY)")
                .pathsToMatch("/api/v1/integration/**")
                .build();
    }

    /** Everything the web console calls. JWT only. */
    @Bean
    GroupedOpenApi consoleApi() {
        return GroupedOpenApi.builder()
                .group("console")
                .displayName("Console API (JWT)")
                .pathsToExclude("/api/v1/integration/**")
                .build();
    }

    @Bean
    OpenAPI letThemKnowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LetThemKnow API")
                        .version("v1")
                        .description("""
                                Multi-tenant notification and campaign platform (LINE + Email).

                                Two authentication schemes:
                                * **bearerAuth** – JWT from `POST /api/v1/auth/login`, for the web console.
                                * **apiKey** – `X-API-KEY: ltk_<prefix>_<secret>`, accepted only on `/api/v1/integration/**`
                                  and rate limited per key.

                                Every response is `{ code, message, data }`; the HTTP status mirrors `code`.
                                """)
                        .license(new License().name("Proprietary")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT issued by POST /api/v1/auth/login"))
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-KEY")
                                .description("Integration key: ltk_<8 char prefix>_<32 char secret>")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
