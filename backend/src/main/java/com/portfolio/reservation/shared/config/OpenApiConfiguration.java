package com.portfolio.reservation.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI reservationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Reservation Management System API")
                .version("0.1.0")
                .description("Phase 1: executable foundation. Business endpoints and authentication are pending."));
    }
}
