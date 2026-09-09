package com.portfolio.reservation.users.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "roles")
public class Role {
    public enum Name { CLIENTE, EMPLEADO, ADMIN }

    @Id private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 16)
    private Name name;

    protected Role() {}
    public UUID getId() { return id; }
    public Name getName() { return name; }
}
