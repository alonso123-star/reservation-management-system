package com.portfolio.reservation.users.api;

import com.portfolio.reservation.users.domain.Role;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;

public final class AdminUserRequests {
    private AdminUserRequests() {}
    @Schema(name="AdminUserCreate", description="ADMIN creates a user with an explicit permitted role; internal fields are server-controlled.")
    public record Create(@NotBlank @Size(max=100) String name,
            @NotBlank @Email @Size(max=254) String email,
            @NotBlank @Size(min=12, max=72) @Schema(accessMode=Schema.AccessMode.WRITE_ONLY) String password,
            @NotNull Role.Name role) {}
    public record ChangeRole(@NotNull Role.Name role, @NotNull @PositiveOrZero Long version) {}
    public record ChangeStatus(@NotNull Boolean active, @NotNull @PositiveOrZero Long version) {}
    public record Filter(@Size(max=100) String q, Role.Name role, Boolean active,
            @Schema(defaultValue="0", minimum="0", maximum="100000") Integer page,
            @Schema(defaultValue="20", minimum="1", maximum="100") Integer size,
            @Schema(description="name|email|active|createdAt|id,asc|desc; default name,asc; UUID tie-break") String sort) {}
}
