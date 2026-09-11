package com.portfolio.reservation.payments.application;

import com.portfolio.reservation.audit.AuditService;
import com.portfolio.reservation.payments.api.*;
import com.portfolio.reservation.payments.domain.*;
import com.portfolio.reservation.payments.infrastructure.*;
import com.portfolio.reservation.reservations.application.ReservationAccess;
import com.portfolio.reservation.reservations.domain.Reservation;
import com.portfolio.reservation.reservations.infrastructure.IdempotencyStore;
import com.portfolio.reservation.shared.api.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly = true)
@PreAuthorize("hasAnyRole('CLIENTE','EMPLEADO','ADMIN')")
public class PaymentService {
    private static final String OPERATION = "PAY_RESERVATION";
    private final ReservationAccess access;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentSimulator simulator;
    private final IdempotencyStore receipts;
    private final AuditService audit;
    private final Clock clock;
    public PaymentService(ReservationAccess access, PaymentRepository payments, RefundRepository refunds,
            PaymentSimulator simulator, IdempotencyStore receipts, AuditService audit, Clock clock) {
        this.access = access; this.payments = payments; this.refunds = refunds; this.simulator = simulator;
        this.receipts = receipts; this.audit = audit; this.clock = clock;
    }
    @Transactional
    public PaymentView pay(UUID reservationId, UUID key, Jwt jwt, UUID requestId) {
        // No preloaded entity: the locking SELECT observes the latest committed cancellation state.
        var reservation = access.lockOwned(reservationId, jwt);
        UUID actor = UUID.fromString(jwt.getSubject());
        var prior = receipts.claim(actor, OPERATION, key, hash(reservationId), clock.instant(), PaymentView.class);
        if (prior != null) return prior;
        if (reservation.getStatus() != Reservation.Status.CONFIRMED)
            throw conflict("RESERVATION_NOT_PAYABLE", "Solo se pueden pagar reservas confirmadas.");
        if (payments.findByReservationIdAndResult(reservationId, Payment.Result.APPROVED).isPresent())
            throw conflict("RESERVATION_ALREADY_PAID", "La reserva ya tiene un pago aprobado.");
        var result = simulator.evaluate(payments.countByReservationId(reservationId));
        var payment = payments.saveAndFlush(new Payment(reservationId, reservation.getTotalAmount(), reservation.getCurrency(), result, actor, clock.instant()));
        audit.record(actor, "PAYMENT_ATTEMPTED", "PAYMENT", payment.getId(), requestId);
        audit.record(actor, result == Payment.Result.APPROVED ? "PAYMENT_APPROVED" : "PAYMENT_DECLINED", "PAYMENT", payment.getId(), requestId);
        var response = PaymentView.from(payment, null);
        receipts.complete(actor, OPERATION, key, reservationId, payment.getId(), response);
        return response;
    }
    public PaymentHistory history(UUID id, Integer page, Integer size, String sort, Jwt jwt) {
        var reservation = access.findOwned(id, jwt);
        var approved = payments.findByReservationIdAndResult(id, Payment.Result.APPROVED);
        boolean refunded = approved.filter(p -> refunds.existsByPaymentId(p.getId())).isPresent();
        var settlement = approved.isEmpty() ? PaymentHistory.Settlement.UNPAID : refunded ? PaymentHistory.Settlement.REFUNDED : PaymentHistory.Settlement.PAID;
        boolean canPay = reservation.getStatus() == Reservation.Status.CONFIRMED && approved.isEmpty();
        var attempts = payments.findAllByReservationId(id, PageView.request(page, size, sort, "createdAt", Set.of("createdAt", "id")));
        var refundMap = refunds.findAllByPaymentIdIn(attempts.getContent().stream().map(Payment::getId).toList()).stream()
                .collect(Collectors.toMap(Refund::getPaymentId, r -> r));
        return new PaymentHistory(settlement, canPay ? reservation.getTotalAmount() : BigDecimal.ZERO, reservation.getCurrency(), canPay,
                PageView.from(attempts.map(p -> PaymentView.from(p, refundMap.get(p.getId())))));
    }
    private static String hash(UUID id) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("payment:v1:" + id).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
