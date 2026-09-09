package com.portfolio.reservation.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id private UUID id;
    @Column(name = "session_id", nullable = false) private UUID sessionId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "consumed_at") private Instant consumedAt;

    protected RefreshToken() {}

    public RefreshToken(UUID sessionId, String hash, Instant now) {
        id = UUID.randomUUID();
        this.sessionId = sessionId;
        tokenHash = hash;
        createdAt = now;
    }

    public void consume(Instant now) { consumedAt = now; }
    public UUID getSessionId() { return sessionId; }
    public boolean isConsumed() { return consumedAt != null; }
}
