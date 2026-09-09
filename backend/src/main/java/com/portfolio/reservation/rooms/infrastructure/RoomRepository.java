package com.portfolio.reservation.rooms.infrastructure;

import com.portfolio.reservation.rooms.domain.Room;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;

public interface RoomRepository extends JpaRepository<Room, UUID>, JpaSpecificationExecutor<Room> {
    @Override @EntityGraph(attributePaths = "roomType")
    Page<Room> findAll(Specification<Room> spec, Pageable pageable);
}
