package com.portfolio.reservation.rooms.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record AvailabilityQuery(
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        @Schema(description = "Inclusive arrival date (hotel calendar)", example = "2026-10-10", requiredMode = Schema.RequiredMode.REQUIRED) LocalDate checkIn,
        @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        @Schema(description = "Exclusive departure date; must be after checkIn", example = "2026-10-12", requiredMode = Schema.RequiredMode.REQUIRED) LocalDate checkOut,
        @NotNull @Min(1) @Schema(description = "Guests in one room", example = "2", requiredMode = Schema.RequiredMode.REQUIRED) Integer guests,
        @Schema(description = "Optional catalog room type UUID", example = "123e4567-e89b-12d3-a456-426614174000") UUID roomTypeId,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2)
        @Schema(description = "Inclusive minimum nightly base price in hotel currency", example = "100.00") BigDecimal minPrice,
        @DecimalMin("0") @Digits(integer = 10, fraction = 2)
        @Schema(description = "Inclusive maximum nightly base price in hotel currency", example = "250.00") BigDecimal maxPrice,
        @Schema(description = "Zero-based page, 0..100000", defaultValue = "0", example = "0") Integer page,
        @Schema(description = "Page size, 1..100", defaultValue = "20", example = "20") Integer size,
        @Schema(description = "code, floor, basePrice, capacity or id followed by ,asc or ,desc. Stable id tie-breaker.", defaultValue = "code,asc", example = "basePrice,asc") String sort) {
}
