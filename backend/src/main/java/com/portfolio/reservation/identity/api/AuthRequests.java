package com.portfolio.reservation.identity.api;

import jakarta.validation.constraints.*;

public final class AuthRequests {
    private AuthRequests() {}

    public record Register(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {}

    public record Login(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 72) String password) {}

    public record PasswordChange(
            @NotBlank @Size(max = 72) String currentPassword,
            @NotBlank @Size(min = 12, max = 72) String newPassword) {}
}
