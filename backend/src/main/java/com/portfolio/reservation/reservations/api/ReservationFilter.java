package com.portfolio.reservation.reservations.api;

import com.portfolio.reservation.reservations.domain.Reservation.Status;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public record ReservationFilter(Status status, @Size(max = 40) String code,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Schema(description = "Inclusive earliest arrival") LocalDate checkInFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Schema(description = "Inclusive latest arrival") LocalDate checkInTo,
        @Schema(description = "Staff only") UUID customerId,
        @Schema(description = "Staff only") UUID roomId,
        @Schema(defaultValue = "0") Integer page, @Schema(defaultValue = "20") Integer size,
        @Schema(description = "createdAt, checkIn, checkOut, code, status, totalAmount or id; asc/desc; stable id tie-breaker", defaultValue = "createdAt,asc") String sort) {}
