package com.portfolio.reservation.rooms.api;

import com.portfolio.reservation.rooms.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class CatalogViews {
    private CatalogViews() {}
    public record PublicType(UUID id, String name, String description, int capacity, BigDecimal basePrice, String currency) {
        public static PublicType from(RoomType t, String currency) {
            return new PublicType(t.getId(), t.getName(), t.getDescription(), t.getCapacity(), t.getBasePrice(), currency);
        }
    }
    public record PublicRoom(UUID id, String code, int floor, PublicType roomType) {
        public static PublicRoom from(Room r, String currency) {
            return new PublicRoom(r.getId(), r.getCode(), r.getFloor(), PublicType.from(r.getRoomType(), currency));
        }
    }
    public record TypeView(UUID id, String name, String description, int capacity, BigDecimal basePrice, String currency,
            boolean active, long version, Instant createdAt, Instant updatedAt) {
        public static TypeView from(RoomType t, String currency) {
            return new TypeView(t.getId(), t.getName(), t.getDescription(), t.getCapacity(), t.getBasePrice(), currency,
                    t.isActive(), t.getVersion(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }
    public record RoomView(UUID id, String code, int floor, TypeView roomType, Room.OperationalStatus operationalStatus,
            boolean active, long version, Instant createdAt, Instant updatedAt) {
        public static RoomView from(Room r, String currency) {
            return new RoomView(r.getId(), r.getCode(), r.getFloor(), TypeView.from(r.getRoomType(), currency),
                    r.getOperationalStatus(), r.isActive(), r.getVersion(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }
}
