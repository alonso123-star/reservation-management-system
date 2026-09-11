package com.portfolio.reservation.reception.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.*;

@Schema(description = "Staff-only detail capabilities evaluated with the hotel Clock. Advisory: each command revalidates under the Reservation lock.")
public record ReceptionView(String customerName, String hotelTimeZone, LocalDate hotelDate, Instant evaluatedAt,
        Instant arrivalDeadline, boolean fullyPaid, boolean canCheckIn, boolean canCheckOut, boolean canNoShow) {}
