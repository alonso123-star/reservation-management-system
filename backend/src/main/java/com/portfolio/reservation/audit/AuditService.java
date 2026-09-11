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
    private final tools.jackson.databind.ObjectMapper json;

    public AuditService(JdbcTemplate jdbc, Clock clock, tools.jackson.databind.ObjectMapper json) { this.jdbc = jdbc; this.clock = clock; this.json=json; }

    public void recordChanges(UUID actor, String action, String resource, UUID id, UUID requestId, java.util.Map<String,Object> changes) {
        var safe=AuditChanges.sanitize(json.valueToTree(changes));
        if(!safe.equals(changes)) throw new IllegalArgumentException("Unsupported audit changes");
        jdbc.update("INSERT INTO audit_events(id,actor_id,action,resource_type,resource_id,occurred_at,request_id,changes) VALUES(?,?,?,?,?,?,?,CAST(? AS jsonb))",
                UUID.randomUUID(),actor,action,resource,id,Timestamp.from(clock.instant()),requestId,json.writeValueAsString(safe));
    }

    public void record(UUID actor, String action, String resource, UUID id, UUID requestId) {
        jdbc.update("INSERT INTO audit_events (id, actor_id, action, resource_type, resource_id, occurred_at, request_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), actor, action, resource, id, Timestamp.from(clock.instant()), requestId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void authenticationFailure(UUID requestId) {
        record(null, "LOGIN_FAILED", "AUTH", null, requestId);
    }
}
