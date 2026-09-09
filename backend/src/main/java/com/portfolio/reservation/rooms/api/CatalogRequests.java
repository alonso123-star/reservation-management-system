package com.portfolio.reservation.rooms.api;

import com.portfolio.reservation.rooms.domain.Room.OperationalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public final class CatalogRequests {
    private CatalogRequests() {}
    public record TypeCreate(
            @NotBlank @Size(max = 100) String name, @NotNull @Size(max = 2000) String description,
            @NotNull @Min(1) @Max(1000) Integer capacity,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal basePrice,
            @NotNull Boolean active) {}
    @Schema(description = "Partial update: omitted/null fields remain unchanged. version is mandatory.")
    public record TypePatch(@NotNull @Min(0) Long version,
            @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*") String name,
            @Size(max = 2000) String description, @Min(1) @Max(1000) Integer capacity,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal basePrice,
            Boolean active) {}
    public record RoomCreate(@NotBlank @Size(max = 30) String code, @NotNull UUID roomTypeId,
            @NotNull @Min(-5) @Max(200) Integer floor, @NotNull OperationalStatus operationalStatus, @NotNull Boolean active) {}
    @Schema(description = "Partial update: omitted/null fields remain unchanged. version is mandatory.")
    public record RoomPatch(@NotNull @Min(0) Long version,
            @Size(max = 30) @Pattern(regexp = "(?s).*\\S.*") String code, UUID roomTypeId,
            @Min(-5) @Max(200) Integer floor, OperationalStatus operationalStatus, Boolean active) {}
    public record StatusPatch(@NotNull @Min(0) Long version, @NotNull OperationalStatus operationalStatus) {}
}
