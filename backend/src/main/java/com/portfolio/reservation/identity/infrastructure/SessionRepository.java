package com.portfolio.reservation.identity.infrastructure;

import com.portfolio.reservation.identity.domain.RefreshSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository extends JpaRepository<RefreshSession, UUID> {
    @Query("select s.userId from RefreshSession s where s.id = :id")
    Optional<UUID> findOwnerId(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.id = :id")
    Optional<RefreshSession> lockById(@Param("id") UUID id);

    @Query("select s from RefreshSession s where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now order by s.createdAt desc")
    List<RefreshSession> findActive(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("update RefreshSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    void revokeAll(@Param("userId") UUID userId, @Param("now") Instant now);
}
