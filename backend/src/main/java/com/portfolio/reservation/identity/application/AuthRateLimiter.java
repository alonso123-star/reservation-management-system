package com.portfolio.reservation.identity.application;

import com.portfolio.reservation.shared.api.ApiException;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuthRateLimiter(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void check(String bucket, int limit, Duration window) {
        Instant now = clock.instant();
        int attempts = jdbc.queryForObject("""
                INSERT INTO auth_rate_limits (bucket_hash, window_start, attempts) VALUES (?, ?, 1)
                ON CONFLICT (bucket_hash) DO UPDATE SET
                  attempts = CASE WHEN auth_rate_limits.window_start <= ? THEN 1 ELSE auth_rate_limits.attempts + 1 END,
                  window_start = CASE WHEN auth_rate_limits.window_start <= ? THEN EXCLUDED.window_start ELSE auth_rate_limits.window_start END
                RETURNING attempts
                """, Integer.class, TokenService.hash(bucket), Timestamp.from(now),
                Timestamp.from(now.minus(window)), Timestamp.from(now.minus(window)));
        if (attempts > limit) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED", "Demasiados intentos. Inténtalo más tarde.");
    }
}
