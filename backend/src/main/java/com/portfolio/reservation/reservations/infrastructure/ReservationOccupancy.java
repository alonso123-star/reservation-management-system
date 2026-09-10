package com.portfolio.reservation.reservations.infrastructure;

import com.portfolio.reservation.reservations.domain.Reservation;
import com.portfolio.reservation.rooms.domain.Room;
import jakarta.persistence.criteria.*;
import java.time.LocalDate;

public final class ReservationOccupancy {
    private ReservationOccupancy() {}
    public static Predicate free(Root<Room> room, CriteriaQuery<?> query, CriteriaBuilder cb,
            LocalDate checkIn, LocalDate checkOut) {
        var occupied = query.subquery(Integer.class);
        var r = occupied.from(Reservation.class);
        occupied.select(cb.literal(1)).where(cb.equal(r.get("room").get("id"), room.get("id")),
                r.get("status").in(Reservation.BLOCKING), cb.lessThan(r.get("checkIn"), checkOut),
                cb.greaterThan(r.get("checkOut"), checkIn));
        return cb.not(cb.exists(occupied));
    }
}
