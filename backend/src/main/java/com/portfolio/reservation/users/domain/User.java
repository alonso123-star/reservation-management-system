package com.portfolio.reservation.users.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id private UUID id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, unique = true, length = 254) private String email;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Column(nullable = false) private boolean active;
    @Column(name = "security_version", nullable = false) private long securityVersion;
    @Version private long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected User() {}

    public User(String name, String email, String passwordHash, Role role, Instant now) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void changePassword(String hash, Instant now) {
        passwordHash = hash;
        securityVersion++;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public void changeRole(Role value, Instant now) { role = value; securityVersion++; updatedAt = now; }
    public void changeActive(boolean value, Instant now) { active = value; securityVersion++; updatedAt = now; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Role getRole() { return role; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return active; }
    public long getSecurityVersion() { return securityVersion; }
    public Instant getCreatedAt() { return createdAt; }
}
