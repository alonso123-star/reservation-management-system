package com.portfolio.reservation.reservations.infrastructure;

import com.portfolio.reservation.reservations.api.ReservationView;
import com.portfolio.reservation.shared.api.ApiException;
import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class IdempotencyStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public IdempotencyStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public ReservationView claim(UUID actor, String operation, UUID key, String hash, Instant now) {
        return claim(actor, operation, key, hash, now, ReservationView.class);
    }
    public <T> T claim(UUID actor, String operation, UUID key, String hash, Instant now, Class<T> responseType) {
        int inserted = jdbc.update("""
                INSERT INTO idempotency_requests(id,actor_id,operation,request_key,request_hash,created_at,expires_at)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT (actor_id,operation,request_key) DO NOTHING
                """, UUID.randomUUID(), actor, operation, key, hash, Timestamp.from(now), Timestamp.from(now.plus(Duration.ofHours(24))));
        if (inserted == 1) return null;
        // The unique insert waits for the competing transaction. This next statement sees its committed receipt.
        var receipt = jdbc.queryForMap("SELECT request_hash,response_json,expires_at FROM idempotency_requests WHERE actor_id=? AND operation=? AND request_key=? FOR UPDATE", actor, operation, key);
        if (!hash.equals(receipt.get("request_hash"))) throw conflict("IDEMPOTENCY_KEY_REUSED", "La clave ya se utilizó para otra petición.");
        if (!((Timestamp) receipt.get("expires_at")).toInstant().isAfter(now))
            throw conflict("IDEMPOTENCY_KEY_EXPIRED", "La clave venció. Consulta tu historial antes de iniciar otra reserva.");
        if (receipt.get("response_json") == null) throw conflict("REQUEST_IN_PROGRESS", "La operación aún no tiene un resultado.");
        return json.readValue((String) receipt.get("response_json"), responseType);
    }
    public void complete(UUID actor, String operation, UUID key, ReservationView response) {
        complete(actor, operation, key, response.id(), null, response);
    }
    public void complete(UUID actor, String operation, UUID key, UUID reservationId, UUID paymentId, Object response) {
        int count = jdbc.update("UPDATE idempotency_requests SET reservation_id=?,payment_id=?,response_json=? WHERE actor_id=? AND operation=? AND request_key=? AND reservation_id IS NULL",
                reservationId, paymentId, json.writeValueAsString(response), actor, operation, key);
        if (count != 1) throw new IllegalStateException("Idempotency receipt was not claimed");
    }
    private static ApiException conflict(String code, String detail) { return new ApiException(HttpStatus.CONFLICT, code, detail); }
}
