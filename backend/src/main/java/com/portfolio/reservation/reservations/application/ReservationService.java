package com.portfolio.reservation.reservations.application;

import com.portfolio.reservation.audit.AuditService;
import com.portfolio.reservation.payments.application.RefundService;
import com.portfolio.reservation.reservations.api.*;
import com.portfolio.reservation.reservations.domain.Reservation;
import com.portfolio.reservation.reservations.infrastructure.*;
import com.portfolio.reservation.rooms.api.AvailabilityQuery;
import com.portfolio.reservation.rooms.application.StayRules;
import com.portfolio.reservation.rooms.infrastructure.*;
import com.portfolio.reservation.shared.api.*;
import com.portfolio.reservation.users.infrastructure.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReservationService {
    private final ReservationRepository reservations;
    private final RoomRepository rooms;
    private final RoomTypeRepository types;
    private final UserRepository users;
    private final IdempotencyStore receipts;
    private final AuditService audit;
    private final Clock clock;
    private final ZoneId zone;
    private final String currency;
    private final ReservationAccess access;
    private final RefundService refunds;
    public ReservationService(ReservationRepository reservations, RoomRepository rooms, RoomTypeRepository types,
            UserRepository users, IdempotencyStore receipts, AuditService audit, Clock clock,
            @Value("${hotel.time-zone:America/Lima}") String zone, @Value("${hotel.currency:PEN}") String currency,
            ReservationAccess access, RefundService refunds) {
        this.reservations = reservations; this.rooms = rooms; this.types = types; this.users = users;
        this.receipts = receipts; this.audit = audit; this.clock = clock;
        this.zone = ZoneId.of(zone); this.currency = Currency.getInstance(currency).getCurrencyCode();
        this.access = access; this.refunds = refunds;
    }
    @Transactional @PreAuthorize("hasRole('CLIENTE')")
    public ReservationView create(ReservationRequests.Create body, UUID key, Jwt jwt, UUID requestId) {
        return createFor(body, actor(jwt), key, jwt, requestId, "CREATE_RESERVATION");
    }
    @Transactional @PreAuthorize("hasAnyRole('EMPLEADO','ADMIN')")
    public ReservationView staffCreate(ReservationRequests.StaffCreate body, UUID key, Jwt jwt, UUID requestId) {
        return createFor(body.stay(), body.customerId(), key, jwt, requestId, "STAFF_CREATE_RESERVATION");
    }
    private ReservationView createFor(ReservationRequests.Create b, UUID customer, UUID key, Jwt jwt, UUID requestId, String operation) {
        long nights = StayRules.nights(b.checkIn(), b.checkOut());
        String hash = hash(customer + "|" + b.roomId() + "|" + b.checkIn() + "|" + b.checkOut() + "|" + b.guests());
        var prior = receipts.claim(actor(jwt), operation, key, hash, clock.instant());
        if (prior != null) return prior;
        users.findById(customer).filter(u -> u.isActive() && u.getRole().getName().name().equals("CLIENTE"))
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Cliente no encontrado."));
        // Consistent protocol with catalog edits: room first, then its type, held until commit.
        var room = rooms.lockById(b.roomId()).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Habitación no encontrada."));
        var type = types.lockById(room.getRoomType().getId()).orElseThrow();
        var filter = new AvailabilityQuery(b.checkIn(), b.checkOut(), b.guests(), type.getId(), null, null, null, null, null);
        if (!rooms.exists(AvailabilitySpecifications.matching(filter).and((root, query, cb) -> cb.equal(root.get("id"), room.getId()))))
            throw error(HttpStatus.CONFLICT, "ROOM_NOT_AVAILABLE", "La habitación ya no está disponible para esas fechas y huéspedes. Actualiza la búsqueda.");
        BigDecimal total = type.getBasePrice().multiply(BigDecimal.valueOf(nights));
        if (total.compareTo(new BigDecimal("9999999999.99")) > 0)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "El importe de la estancia supera el máximo admitido.");
        var reservation = reservations.saveAndFlush(new Reservation(customer, actor(jwt), room, b.checkIn(), b.checkOut(),
                b.guests(), type.getBasePrice(), total, currency, clock.instant()));
        audit.record(actor(jwt), "RESERVATION_CREATED", "RESERVATION", reservation.getId(), requestId);
        var response = view(reservation, jwt);
        receipts.complete(actor(jwt), operation, key, response);
        return response;
    }
    @PreAuthorize("hasAnyRole('CLIENTE','EMPLEADO','ADMIN')")
    public PageView<ReservationView> history(ReservationFilter f, Jwt jwt) {
        if (!staff(jwt) && (f.customerId() != null || f.roomId() != null))
            throw error(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Los filtros de cliente y habitación son exclusivos del personal.");
        if (f.checkInFrom() != null && f.checkInTo() != null && f.checkInFrom().isAfter(f.checkInTo()))
            throw error(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "El rango de fechas está invertido.");
        return PageView.from(reservations.findAll((root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (!staff(jwt)) predicates.add(cb.equal(root.get("customerId"), actor(jwt)));
            if (f.customerId() != null) predicates.add(cb.equal(root.get("customerId"), f.customerId()));
            if (f.roomId() != null) predicates.add(cb.equal(root.get("room").get("id"), f.roomId()));
            if (f.status() != null) predicates.add(cb.equal(root.get("status"), f.status()));
            if (f.code() != null && !f.code().isBlank()) predicates.add(cb.equal(root.get("code"), f.code().strip().toUpperCase(Locale.ROOT)));
            if (f.checkInFrom() != null) predicates.add(cb.greaterThanOrEqualTo(root.get("checkIn"), f.checkInFrom()));
            if (f.checkInTo() != null) predicates.add(cb.lessThanOrEqualTo(root.get("checkIn"), f.checkInTo()));
            return cb.and(predicates.toArray(Predicate[]::new));
        }, PageView.request(f.page(), f.size(), f.sort(), "createdAt", Set.of("createdAt", "checkIn", "checkOut", "code", "status", "totalAmount", "id")))
                .map(r -> view(r, jwt)));
    }
    @PreAuthorize("hasAnyRole('CLIENTE','EMPLEADO','ADMIN')")
    public ReservationView detail(UUID id, Jwt jwt) { return view(owned(id, jwt), jwt); }

    @Transactional @PreAuthorize("hasAnyRole('CLIENTE','EMPLEADO','ADMIN')")
    public ReservationView cancel(UUID id, ReservationRequests.Cancel body, Jwt jwt, UUID requestId) {
        var r = access.lockOwned(id, jwt);
        if (r.getVersion() != body.version()) throw error(HttpStatus.CONFLICT, "STALE_VERSION", "La reserva cambió. Recarga antes de cancelar.");
        if (r.getStatus() != Reservation.Status.CONFIRMED)
            throw error(HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE", "Solo se pueden cancelar reservas confirmadas antes del check-in.");
        if (!staff(jwt) && !today().isBefore(r.getCheckIn()))
            throw error(HttpStatus.BAD_REQUEST, "CANCELLATION_NOT_ALLOWED", "El cliente debe cancelar antes del día de entrada.");
        refunds.refundForCancellation(id, body.reason(), actor(jwt), requestId);
        r.cancel(body.reason(), actor(jwt), clock.instant());
        reservations.flush();
        audit.record(actor(jwt), "RESERVATION_CANCELLED", "RESERVATION", id, requestId);
        return view(r, jwt);
    }
    private Reservation owned(UUID id, Jwt jwt) {
        return access.findOwned(id, jwt);
    }
    private ReservationView view(Reservation r, Jwt jwt) { return ReservationView.from(r, staff(jwt), today()); }
    private LocalDate today() { return LocalDate.now(clock.withZone(zone)); }
    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static boolean staff(Jwt jwt) { return Set.of("EMPLEADO", "ADMIN").contains(jwt.getClaimAsString("role")); }
    private static ApiException error(HttpStatus status, String code, String detail) { return new ApiException(status, code, detail); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
