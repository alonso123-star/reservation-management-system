package com.portfolio.reservation.payments.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "refunds")
public class Refund {
    @Id private UUID id;
    @Column(name = "payment_id", nullable = false, unique = true) private UUID paymentId;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(nullable = false, length = 500) private String reason;
    @Column(name = "actor_id", nullable = false) private UUID actorId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected Refund() {}
    public Refund(Payment payment, String reason, UUID actorId, Instant now) {
        if (payment.getResult() != Payment.Result.APPROVED) throw new IllegalArgumentException("Only approved payments can be refunded");
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new IllegalArgumentException("A refund reason is required");
        id = UUID.randomUUID(); paymentId = payment.getId(); amount = payment.getAmount(); currency = payment.getCurrency();
        this.reason = reason.strip(); this.actorId = actorId; createdAt = now;
    }
    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public UUID getActorId() { return actorId; }
    public Instant getCreatedAt() { return createdAt; }
}
