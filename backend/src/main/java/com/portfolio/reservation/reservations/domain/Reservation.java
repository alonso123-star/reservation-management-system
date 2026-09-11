package com.portfolio.reservation.reservations.domain;

import com.portfolio.reservation.rooms.domain.Room;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Entity
@Table(name = "reservations")
public class Reservation {
    public enum Status { CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED, NO_SHOW }
    public static final Set<Status> BLOCKING = Set.of(Status.CONFIRMED, Status.CHECKED_IN, Status.CHECKED_OUT);
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 40) private String code;
    @Column(name = "customer_id", nullable = false) private UUID customerId;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "room_id", nullable = false) private Room room;
    @Column(name = "check_in", nullable = false) private LocalDate checkIn;
    @Column(name = "check_out", nullable = false) private LocalDate checkOut;
    @Column(nullable = false) private int guests;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Status status;
    @Column(name = "agreed_nightly_rate", nullable = false, precision = 12, scale = 2) private BigDecimal agreedNightlyRate;
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2) private BigDecimal totalAmount;
    @Column(nullable = false, length = 3) private String currency;
    @Column(name = "cancellation_reason", length = 500) private String cancellationReason;
    @Column(name = "cancelled_by") private UUID cancelledBy;
    @Column(name = "cancelled_at") private Instant cancelledAt;
    @Column(name = "checked_in_at") private Instant checkedInAt;
    @Column(name = "checked_out_at") private Instant checkedOutAt;
    @Version private long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Reservation() {}
    public Reservation(UUID customerId, UUID createdBy, Room room, LocalDate checkIn, LocalDate checkOut,
            int guests, BigDecimal rate, BigDecimal total, String currency, Instant now) {
        id = UUID.randomUUID();
        code = "R-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        this.customerId = customerId; this.createdBy = createdBy; this.room = room;
        this.checkIn = checkIn; this.checkOut = checkOut; this.guests = guests;
        status = Status.CONFIRMED; agreedNightlyRate = rate; totalAmount = total;
        this.currency = currency; createdAt = now; updatedAt = now;
    }
    public void cancel(String reason, UUID actor, Instant now) {
        status = Status.CANCELLED; cancellationReason = reason.strip();
        cancelledBy = actor; cancelledAt = now; updatedAt = now;
    }
    public void checkIn(Instant now) {
        requireStatus(Status.CONFIRMED);
        if (checkedInAt != null) throw new IllegalStateException("Check-in timestamp already exists");
        status = Status.CHECKED_IN; checkedInAt = now; updatedAt = now;
    }
    public void checkOut(Instant now) {
        requireStatus(Status.CHECKED_IN);
        if (checkedInAt == null || checkedOutAt != null || now.isBefore(checkedInAt))
            throw new IllegalStateException("Invalid checkout timestamps");
        status = Status.CHECKED_OUT; checkedOutAt = now; updatedAt = now;
    }
    public void noShow(Instant now) {
        requireStatus(Status.CONFIRMED);
        status = Status.NO_SHOW; updatedAt = now;
    }
    private void requireStatus(Status expected) {
        if (status != expected) throw new IllegalStateException("Incompatible reservation transition");
    }
    public UUID getId() { return id; }
    public String getCode() { return code; }
    public UUID getCustomerId() { return customerId; }
    public UUID getCreatedBy() { return createdBy; }
    public Room getRoom() { return room; }
    public LocalDate getCheckIn() { return checkIn; }
    public LocalDate getCheckOut() { return checkOut; }
    public int getGuests() { return guests; }
    public Status getStatus() { return status; }
    public BigDecimal getAgreedNightlyRate() { return agreedNightlyRate; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getCancellationReason() { return cancellationReason; }
    public UUID getCancelledBy() { return cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCheckedInAt() { return checkedInAt; }
    public Instant getCheckedOutAt() { return checkedOutAt; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
