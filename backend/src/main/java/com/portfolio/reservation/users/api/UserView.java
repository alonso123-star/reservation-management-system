package com.portfolio.reservation.users.api;

import com.portfolio.reservation.users.domain.User;
import java.time.Instant;
import java.util.UUID;

public record UserView(UUID id, String name, String email, String role, Instant createdAt) {
    public static UserView from(User user) {
        return new UserView(user.getId(), user.getName(), user.getEmail(),
                user.getRole().getName().name(), user.getCreatedAt());
    }
}
