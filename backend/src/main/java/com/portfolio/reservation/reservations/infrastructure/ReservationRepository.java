package com.portfolio.reservation.reservations.infrastructure;

import com.portfolio.reservation.reservations.domain.Reservation;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;

public interface ReservationRepository extends JpaRepository<Reservation, UUID>, JpaSpecificationExecutor<Reservation> {
    @Override @EntityGraph(attributePaths = {"room", "room.roomType"})
    Page<Reservation> findAll(Specification<Reservation> spec, Pageable pageable);
}
