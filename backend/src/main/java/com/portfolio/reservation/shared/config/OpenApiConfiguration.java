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
                .version("0.2.0")
                .description("Identity API. Mutations require an allowed Origin and the CSRF cookie/header from GET /api/v1/auth/csrf. Public registration only creates CLIENTE users."));
    }
}
