package com.portfolio.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIT {

    @Container
    static final PostgreSQLContainer DATABASE = new PostgreSQLContainer("postgres:18.6-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", DATABASE::getUsername);
        registry.add("spring.datasource.password", DATABASE::getPassword);
        registry.add("security.auth.jwt-secret", () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void appliesTheInfrastructureMigrationToRealPostgres() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class))
                .contains("flyway_schema_history", "users", "roles", "refresh_sessions", "refresh_tokens")
                .contains("room_types", "rooms")
                .contains("reservations", "idempotency_requests", "payments", "refunds");
    }

    @Test
    void servesReadinessOnlyAfterTheDatabaseIsReachable() throws Exception {
        var response = get("/api/v1/system/health/readiness");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    @Test
    void exposesTheFoundationContractAndOpenApi() throws Exception {
        var response = get("/api/v1/system/info");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"phase\":6", "Reservation Management System");
        var documentation = get("/v3/api-docs");
        assertThat(documentation.statusCode()).isEqualTo(200);
        assertThat(documentation.body()).contains("/api/v1/system/info", "openapi");
    }

    @Test
    void requiresAuthenticationForUserResources() throws Exception {
        assertThat(get("/api/v1/users/me").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
