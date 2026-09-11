package com.portfolio.reservation.reception.application;

import com.portfolio.reservation.audit.AuditService;
import com.portfolio.reservation.payments.application.RefundService;
import com.portfolio.reservation.reception.api.ReceptionView;
import com.portfolio.reservation.reservations.api.ReservationView;
import com.portfolio.reservation.reservations.application.ReservationAccess;
import com.portfolio.reservation.reservations.domain.Reservation;
import com.portfolio.reservation.reservations.infrastructure.ReservationRepository;
import com.portfolio.reservation.shared.api.ApiException;
import com.portfolio.reservation.users.infrastructure.UserRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('EMPLEADO','ADMIN')")
public class ReceptionService {
    public enum Action { CHECK_IN, CHECK_OUT, NO_SHOW }
    private final ReservationAccess access;
    private final ReservationRepository reservations;
    private final RefundService payments;
    private final ReceptionPolicy policy;
    private final AuditService audit;
    private final UserRepository users;
    public ReceptionService(ReservationAccess access, ReservationRepository reservations, RefundService payments,
            ReceptionPolicy policy, AuditService audit, UserRepository users) {
        this.access = access; this.reservations = reservations; this.payments = payments;
        this.policy = policy; this.audit = audit; this.users = users;
    }
    public ReservationView detail(UUID id, Jwt jwt) { return describe(access.findOwned(id, jwt)); }
    @Transactional
    public ReservationView transition(UUID id, long version, Action action, Jwt jwt, UUID requestId) {
        // Same first lock as payment/cancellation, with no earlier load into the persistence context.
        var r = access.lockOwned(id, jwt);
        if (r.getVersion() != version) throw conflict("STALE_VERSION", "La reserva cambió. Actualiza antes de continuar.");
        var expected = action == Action.CHECK_OUT ? Reservation.Status.CHECKED_IN : Reservation.Status.CONFIRMED;
        if (r.getStatus() != expected) throw conflict("INVALID_RESERVATION_STATE", "El estado actual no permite esta operación de recepción.");
        var now = policy.now();
        switch (action) {
            case CHECK_IN -> {
                if (!policy.withinStay(r, now)) throw conflict("CHECK_IN_OUTSIDE_STAY", "El ingreso debe realizarse dentro del periodo reservado, sin incluir el día de salida.");
                if (!payments.isFullyPaid(id)) throw conflict("RESERVATION_NOT_FULLY_PAID", "Se requiere el pago completo aprobado y no reembolsado.");
                r.checkIn(now);
            }
            case CHECK_OUT -> {
                if (r.getCheckedInAt() == null || r.getCheckedOutAt() != null || now.isBefore(r.getCheckedInAt()))
                    throw conflict("INVALID_RECEPTION_TIMESTAMP", "Las fechas operativas no permiten registrar la salida.");
                r.checkOut(now);
            }
            case NO_SHOW -> {
                if (!policy.pastDeadline(r, now)) throw conflict("ARRIVAL_DEADLINE_NOT_PASSED", "Todavía no ha pasado el plazo de llegada del hotel.");
                r.noShow(now);
            }
        }
        reservations.flush();
        String event = switch (action) {
            case CHECK_IN -> "RESERVATION_CHECKED_IN";
            case CHECK_OUT -> "RESERVATION_CHECKED_OUT";
            case NO_SHOW -> "RESERVATION_NO_SHOW";
        };
        audit.record(UUID.fromString(jwt.getSubject()), event, "RESERVATION", id, requestId);
        return describe(r);
    }
    private ReservationView describe(Reservation r) {
        var now = policy.now();
        boolean paid = payments.isFullyPaid(r.getId());
        boolean confirmed = r.getStatus() == Reservation.Status.CONFIRMED;
        var reception = new ReceptionView(users.findById(r.getCustomerId()).orElseThrow().getName(),
                policy.zone().getId(), policy.date(now), now, policy.deadline(r), paid,
                confirmed && paid && policy.withinStay(r, now), r.getStatus() == Reservation.Status.CHECKED_IN,
                confirmed && policy.pastDeadline(r, now));
        return ReservationView.from(r, true, policy.date(now), reception);
    }
    private static ApiException conflict(String code, String detail) { return new ApiException(HttpStatus.CONFLICT, code, detail); }
}
