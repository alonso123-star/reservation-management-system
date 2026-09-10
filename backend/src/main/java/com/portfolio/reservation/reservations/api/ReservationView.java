package com.portfolio.reservation.reservations.api;

import com.portfolio.reservation.reservations.domain.Reservation;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record ReservationView(UUID id, String code, UUID customerId, UUID createdBy, UUID roomId,
        String roomCode, String roomTypeName, LocalDate checkIn, LocalDate checkOut, int guests,
        long nights, Reservation.Status status, BigDecimal agreedNightlyRate, BigDecimal totalAmount,
        String currency, String cancellationReason, UUID cancelledBy, Instant cancelledAt,
        Instant checkedInAt, Instant checkedOutAt, long version, Instant createdAt, Instant updatedAt,
        boolean canCancel) {
    public static ReservationView from(Reservation r, boolean staff, LocalDate today) {
        return new ReservationView(r.getId(), r.getCode(), r.getCustomerId(), r.getCreatedBy(), r.getRoom().getId(),
                r.getRoom().getCode(), r.getRoom().getRoomType().getName(), r.getCheckIn(), r.getCheckOut(), r.getGuests(),
                ChronoUnit.DAYS.between(r.getCheckIn(), r.getCheckOut()), r.getStatus(), r.getAgreedNightlyRate(),
                r.getTotalAmount(), r.getCurrency(), r.getCancellationReason(), r.getCancelledBy(), r.getCancelledAt(),
                r.getCheckedInAt(), r.getCheckedOutAt(), r.getVersion(), r.getCreatedAt(), r.getUpdatedAt(),
                r.getStatus() == Reservation.Status.CONFIRMED && (staff || today.isBefore(r.getCheckIn())));
    }
}
