package com.portfolio.reservation.payments.infrastructure;

import com.portfolio.reservation.payments.domain.Refund;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    boolean existsByPaymentId(UUID paymentId);
    List<Refund> findAllByPaymentIdIn(Collection<UUID> paymentIds);
}
