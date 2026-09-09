package com.portfolio.reservation.identity.infrastructure;

import com.portfolio.reservation.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String hash);

    @Query("select t.sessionId from RefreshToken t where t.tokenHash = :hash")
    Optional<UUID> findSessionIdByHash(@Param("hash") String hash);
}
