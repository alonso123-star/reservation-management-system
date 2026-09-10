package com.portfolio.reservation.rooms.infrastructure;

import com.portfolio.reservation.rooms.domain.RoomType;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface RoomTypeRepository extends JpaRepository<RoomType, UUID>, JpaSpecificationExecutor<RoomType> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RoomType t where t.id = :id")
    java.util.Optional<RoomType> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
}
