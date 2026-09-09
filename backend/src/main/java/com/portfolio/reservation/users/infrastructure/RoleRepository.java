package com.portfolio.reservation.users.infrastructure;

import com.portfolio.reservation.users.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByName(Role.Name name);
}
