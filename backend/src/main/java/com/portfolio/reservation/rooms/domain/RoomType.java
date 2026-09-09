package com.portfolio.reservation.rooms.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_types")
public class RoomType {
    @Id private UUID id;
    @Column(nullable = false, length = 100) private String name;
    @Column(nullable = false, length = 2000) private String description;
    @Column(nullable = false) private int capacity;
    @Column(name = "base_price", nullable = false, precision = 12, scale = 2) private BigDecimal basePrice;
    @Column(nullable = false) private boolean active;
    @Version private long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected RoomType() {}
    public RoomType(String name, String description, int capacity, BigDecimal basePrice, boolean active, Instant now) {
        id = UUID.randomUUID(); createdAt = now; update(name, description, capacity, basePrice, active, now);
    }
    public void update(String name, String description, int capacity, BigDecimal basePrice, boolean active, Instant now) {
        this.name = name.strip(); this.description = description.strip(); this.capacity = capacity;
        this.basePrice = basePrice.setScale(2); this.active = active; updatedAt = now;
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getCapacity() { return capacity; }
    public BigDecimal getBasePrice() { return basePrice; }
    public boolean isActive() { return active; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
