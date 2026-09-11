package com.portfolio.reservation.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI reservationOpenApi() {
        return new OpenAPI().components(new Components().addSecuritySchemes("bearerAuth",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .info(new Info()
                .title("Reservation Management System API")
                .version("0.7.0")
                .description("Identity, hotel catalog and public indicative availability API. Availability validates calendar dates and capacity and returns nightly price estimates; it excludes blocking reservations but never holds rooms. Reservation creation is protected by persisted idempotency and PostgreSQL exclusion; cancellation preserves history. Mutations require an allowed Origin. Cookie-based operations require the CSRF cookie/header from GET /api/v1/auth/csrf; the frontend also sends it with bearer requests. Public registration only creates CLIENTE users. Catalog reads are public; inventory reads and room status changes require EMPLEADO or ADMIN; other catalog writes require ADMIN. PATCH requires the current version and returns 409 on conflict. Catalog activity is not date-based availability."));
    }
}
