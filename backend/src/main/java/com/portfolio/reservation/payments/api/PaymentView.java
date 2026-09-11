package com.portfolio.reservation.payments.api;

import com.portfolio.reservation.payments.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentView(UUID id, UUID reservationId, BigDecimal amount, String currency,
        Payment.Result result, String simulatedReference, UUID actorId, Instant createdAt, RefundView refund) {
    public record RefundView(UUID id, UUID paymentId, BigDecimal amount, String currency, String reason, UUID actorId, Instant createdAt) {}
    public static PaymentView from(Payment p, Refund r) {
        return new PaymentView(p.getId(), p.getReservationId(), p.getAmount(), p.getCurrency(), p.getResult(),
                p.getSimulatedReference(), p.getActorId(), p.getCreatedAt(), r == null ? null :
                new RefundView(r.getId(), r.getPaymentId(), r.getAmount(), r.getCurrency(), r.getReason(), r.getActorId(), r.getCreatedAt()));
    }
}
