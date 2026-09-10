package com.portfolio.reservation.rooms.application;

import com.portfolio.reservation.shared.api.ApiException;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Called only while the catalog row is locked, following the booking protocol. */
@Component
public class CatalogReservationGuard {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;
    public CatalogReservationGuard(JdbcTemplate jdbc, Clock clock, @Value("${hotel.time-zone:America/Lima}") String zone) {
        this.jdbc = jdbc; this.clock = clock; this.zone = ZoneId.of(zone);
    }
    public void room(UUID id) {
        reject(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE room_id=? AND status IN ('CONFIRMED','CHECKED_IN') AND check_out>?",
                Long.class, id, LocalDate.now(clock.withZone(zone))));
    }
    public void type(UUID id, boolean active, int capacity) {
        reject(jdbc.queryForObject("""
                SELECT count(*) FROM reservations r JOIN rooms room ON room.id=r.room_id
                WHERE room.room_type_id=? AND r.status IN ('CONFIRMED','CHECKED_IN') AND r.check_out>?
                AND (NOT ? OR r.guests>?)
                """, Long.class, id, LocalDate.now(clock.withZone(zone)), active, capacity));
    }
    private void reject(long count) {
        if (count > 0) throw new ApiException(HttpStatus.CONFLICT, "ROOM_HAS_RESERVATIONS", "El cambio afectaría reservas activas. Resuélvelas antes de modificar el catálogo.");
    }
}
