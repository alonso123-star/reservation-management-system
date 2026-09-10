package com.portfolio.reservation.rooms.infrastructure;

import com.portfolio.reservation.rooms.api.AvailabilityQuery;
import com.portfolio.reservation.rooms.domain.Room;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import org.springframework.data.jpa.domain.Specification;

public final class AvailabilitySpecifications {
    private AvailabilitySpecifications() {}

    public static Specification<Room> matching(AvailabilityQuery filter) {
        return (root, query, cb) -> {
            var type = root.get("roomType");
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.isTrue(root.get("active")));
            predicates.add(cb.isTrue(type.get("active")));
            predicates.add(cb.equal(root.get("operationalStatus"), Room.OperationalStatus.ACTIVE));
            predicates.add(cb.ge(type.get("capacity"), filter.guests()));
            if (filter.roomTypeId() != null) predicates.add(cb.equal(type.get("id"), filter.roomTypeId()));
            if (filter.minPrice() != null) predicates.add(cb.greaterThanOrEqualTo(type.get("basePrice"), filter.minPrice()));
            if (filter.maxPrice() != null) predicates.add(cb.lessThanOrEqualTo(type.get("basePrice"), filter.maxPrice()));
            predicates.add(com.portfolio.reservation.reservations.infrastructure.ReservationOccupancy.free(
                    root, query, cb, filter.checkIn(), filter.checkOut()));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
