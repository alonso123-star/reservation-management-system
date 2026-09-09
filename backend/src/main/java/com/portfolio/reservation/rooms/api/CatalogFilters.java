package com.portfolio.reservation.rooms.api;

import com.portfolio.reservation.rooms.domain.Room.OperationalStatus;
import jakarta.validation.constraints.*;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public final class CatalogFilters {
    private CatalogFilters() {}
    public record Types(@Size(max = 100) String q, Boolean active,
            @Min(1) @Max(1000) Integer minCapacity, @Min(1) @Max(1000) Integer maxCapacity,
            @Schema(description = "Zero-based page", minimum = "0", maximum = "100000", defaultValue = "0") Integer page,
            @Schema(description = "Items per page", minimum = "1", maximum = "100", defaultValue = "20") Integer size,
            @Schema(description = "name|capacity|basePrice|id,asc|desc; default name,asc") String sort) {}
    public record Rooms(@Size(max = 30) String q, UUID roomTypeId, @Min(-5) @Max(200) Integer floor,
            OperationalStatus operationalStatus, Boolean active,
            @Schema(description = "Zero-based page", minimum = "0", maximum = "100000", defaultValue = "0") Integer page,
            @Schema(description = "Items per page", minimum = "1", maximum = "100", defaultValue = "20") Integer size,
            @Schema(description = "code|floor|operationalStatus|id,asc|desc; default code,asc") String sort) {}
}
