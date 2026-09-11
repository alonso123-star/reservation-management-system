package com.portfolio.reservation.payments.application;

import com.portfolio.reservation.audit.AuditService;
import com.portfolio.reservation.payments.domain.*;
import com.portfolio.reservation.payments.infrastructure.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RefundService {
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final AuditService audit;
    private final Clock clock;
    public RefundService(PaymentRepository payments, RefundRepository refunds, AuditService audit, Clock clock) {
        this.payments = payments; this.refunds = refunds; this.audit = audit; this.clock = clock;
    }
    /** Internal operation, only after the caller locks and authorizes cancellation of Reservation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundForCancellation(UUID reservationId, String reason, UUID actor, UUID requestId) {
        payments.findByReservationIdAndResult(reservationId, Payment.Result.APPROVED).ifPresent(payment -> {
            if (refunds.existsByPaymentId(payment.getId())) return;
            var refund = refunds.saveAndFlush(new Refund(payment, reason, actor, clock.instant()));
            audit.record(actor, "REFUND_CREATED", "REFUND", refund.getId(), requestId);
        });
    }
    /** Future reception precondition only; this method never performs check-in. */
    @Transactional(readOnly = true)
    public boolean isFullyPaid(UUID reservationId) {
        return payments.findByReservationIdAndResult(reservationId, Payment.Result.APPROVED)
                .filter(payment -> !refunds.existsByPaymentId(payment.getId())).isPresent();
    }
}
