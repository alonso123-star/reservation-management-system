package com.portfolio.reservation.reservations.application;

import com.portfolio.reservation.reservations.domain.Reservation;
import com.portfolio.reservation.reservations.infrastructure.ReservationRepository;
import com.portfolio.reservation.shared.api.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** Shared ownership checks; called inside authorized application-service transactions. */
@Component @Transactional(propagation = Propagation.MANDATORY)
public class ReservationAccess {
    private final ReservationRepository reservations;
    public ReservationAccess(ReservationRepository reservations) { this.reservations = reservations; }
    public Reservation findOwned(UUID id, Jwt jwt) { return authorize(reservations.findById(id), jwt); }
    public Reservation lockOwned(UUID id, Jwt jwt) { return authorize(reservations.lockById(id), jwt); }
    private Reservation authorize(Optional<Reservation> value, Jwt jwt) {
        boolean staff = Set.of("EMPLEADO", "ADMIN").contains(jwt.getClaimAsString("role"));
        return value.filter(r -> staff || r.getCustomerId().equals(UUID.fromString(jwt.getSubject())))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "Reserva no encontrada."));
    }
}
