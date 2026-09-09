package com.portfolio.reservation.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_sessions")
public class RefreshSession {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "security_version", nullable = false) private long securityVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;

    protected RefreshSession() {}

    public RefreshSession(UUID userId, long securityVersion, Instant now, Instant expiresAt) {
        id = UUID.randomUUID();
        this.userId = userId;
        this.securityVersion = securityVersion;
        createdAt = now;
        this.expiresAt = expiresAt;
    }

    public void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }
    public boolean isValid(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public long getSecurityVersion() { return securityVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
