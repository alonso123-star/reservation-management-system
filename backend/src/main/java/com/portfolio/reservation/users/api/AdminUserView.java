package com.portfolio.reservation.users.api;

import com.portfolio.reservation.users.domain.User;
import java.time.Instant;
import java.util.UUID;

public record AdminUserView(UUID id, String name, String email, String role, boolean active,
        long version, Instant createdAt, Instant updatedAt) {
    public static AdminUserView from(User u) {
        return new AdminUserView(u.getId(), u.getName(), u.getEmail(), u.getRole().getName().name(),
                u.isActive(), u.getVersion(), u.getCreatedAt(), u.getUpdatedAt());
    }
}
