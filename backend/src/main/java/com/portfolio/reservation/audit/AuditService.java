package com.portfolio.reservation.audit;

import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public void record(UUID actor, String action, String resource, UUID id, UUID requestId) {
        jdbc.update("INSERT INTO audit_events (id, actor_id, action, resource_type, resource_id, occurred_at, request_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), actor, action, resource, id, Timestamp.from(clock.instant()), requestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void authenticationFailure(UUID requestId) {
        record(null, "LOGIN_FAILED", "AUTH", null, requestId);
    }
}
