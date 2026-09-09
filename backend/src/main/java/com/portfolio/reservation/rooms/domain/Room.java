package com.portfolio.reservation.rooms.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "rooms")
public class Room {
    public enum OperationalStatus { ACTIVE, MAINTENANCE, OUT_OF_SERVICE }
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_type_id", nullable = false) private RoomType roomType;
    @Column(nullable = false, length = 30) private String code;
    @Column(nullable = false) private int floor;
    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 20) private OperationalStatus operationalStatus;
    @Column(nullable = false) private boolean active;
    @Version private long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected Room() {}
    public Room(String code, RoomType type, int floor, OperationalStatus status, boolean active, Instant now) {
        id = UUID.randomUUID(); createdAt = now; update(code, type, floor, status, active, now);
    }
    public void update(String code, RoomType type, int floor, OperationalStatus status, boolean active, Instant now) {
        this.code = code.strip().toUpperCase(Locale.ROOT); roomType = type; this.floor = floor;
        operationalStatus = status; this.active = active; updatedAt = now;
    }
    public UUID getId() { return id; }
    public RoomType getRoomType() { return roomType; }
    public String getCode() { return code; }
    public int getFloor() { return floor; }
    public OperationalStatus getOperationalStatus() { return operationalStatus; }
    public boolean isActive() { return active; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
