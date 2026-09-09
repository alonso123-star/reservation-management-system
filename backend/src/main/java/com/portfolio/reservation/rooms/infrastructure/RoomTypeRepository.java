package com.portfolio.reservation.rooms.infrastructure;

import com.portfolio.reservation.rooms.domain.RoomType;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface RoomTypeRepository extends JpaRepository<RoomType, UUID>, JpaSpecificationExecutor<RoomType> {}
