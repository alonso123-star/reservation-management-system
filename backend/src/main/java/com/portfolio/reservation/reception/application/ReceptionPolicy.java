package com.portfolio.reservation.reception.application;

import com.portfolio.reservation.reservations.domain.Reservation;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One explicit hotel-local policy, used for both commands and UI capabilities. */
@Component
public class ReceptionPolicy {
    private final Clock clock;
    private final ZoneId zone;
    private final LocalTime arrivalDeadline;
    public ReceptionPolicy(Clock clock, @Value("${hotel.time-zone:America/Lima}") String zone,
            @Value("${hotel.reception.arrival-deadline:22:00}") String arrivalDeadline) {
        this.clock = clock; this.zone = ZoneId.of(zone); this.arrivalDeadline = LocalTime.parse(arrivalDeadline);
    }
    public Instant now() { return clock.instant(); }
    public ZoneId zone() { return zone; }
    public LocalDate date(Instant now) { return now.atZone(zone).toLocalDate(); }
    public Instant deadline(Reservation r) { return r.getCheckIn().atTime(arrivalDeadline).atZone(zone).toInstant(); }
    public boolean withinStay(Reservation r, Instant now) {
        var date = date(now);
        return !date.isBefore(r.getCheckIn()) && date.isBefore(r.getCheckOut());
    }
    public boolean pastDeadline(Reservation r, Instant now) { return now.isAfter(deadline(r)); }
}
