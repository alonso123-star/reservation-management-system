package com.portfolio.reservation.identity.application;

import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("security.auth")
public record AuthProperties(
        @NotBlank String jwtSecret,
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration accessTtl,
        @NotNull Duration refreshTtl,
        boolean cookieSecure,
        @NotEmpty List<String> allowedOrigins) {
    public AuthProperties {
        if (accessTtl != null && (accessTtl.isNegative() || accessTtl.isZero() || accessTtl.compareTo(Duration.ofMinutes(15)) > 0))
            throw new IllegalArgumentException("Access TTL must be between 0 and 15 minutes");
        if (refreshTtl != null && (refreshTtl.isNegative() || refreshTtl.isZero() || refreshTtl.compareTo(Duration.ofDays(30)) > 0))
            throw new IllegalArgumentException("Refresh TTL must be between 0 and 30 days");
    }
}
