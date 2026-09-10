package com.portfolio.reservation.reservations.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;

public final class ReservationRequests {
    private ReservationRequests() {}
    public record Create(@NotNull UUID roomId,
            @NotNull @Schema(example = "2027-10-10") LocalDate checkIn,
            @NotNull @Schema(example = "2027-10-12") LocalDate checkOut,
            @NotNull @Min(1) @Schema(example = "2") Integer guests) {}
    public record StaffCreate(@NotNull UUID customerId, @NotNull UUID roomId,
            @NotNull @Schema(example = "2027-10-10") LocalDate checkIn,
            @NotNull @Schema(example = "2027-10-12") LocalDate checkOut,
            @NotNull @Min(1) Integer guests) {
        public Create stay() { return new Create(roomId, checkIn, checkOut, guests); }
    }
    public record Cancel(@NotNull @Min(0) @Schema(example = "0") Long version,
            @NotBlank @Size(max = 500) @Schema(example = "Cambio de planes") String reason) {}
}
