package com.portfolio.reservation.payments.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "payments")
public class Payment {
    public enum Result { APPROVED, DECLINED }
    @Id private UUID id;
    @Column(name = "reservation_id", nullable = false) private UUID reservationId;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Result result;
    @Column(name = "simulated_reference", nullable = false, unique = true, length = 50) private String simulatedReference;
    @Column(name = "actor_id", nullable = false) private UUID actorId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected Payment() {}
    public Payment(UUID reservationId, BigDecimal amount, String currency, Result result, UUID actorId, Instant now) {
        id = UUID.randomUUID(); this.reservationId = reservationId; this.amount = amount; this.currency = currency;
        this.result = result; this.actorId = actorId; createdAt = now;
        simulatedReference = "SIM-P-" + UUID.randomUUID();
    }
    public UUID getId() { return id; }
    public UUID getReservationId() { return reservationId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Result getResult() { return result; }
    public String getSimulatedReference() { return simulatedReference; }
    public UUID getActorId() { return actorId; }
    public Instant getCreatedAt() { return createdAt; }
}
