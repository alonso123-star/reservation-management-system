package com.portfolio.reservation.payments.infrastructure;

import com.portfolio.reservation.payments.domain.Payment;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    long countByReservationId(UUID reservationId);
    Optional<Payment> findByReservationIdAndResult(UUID reservationId, Payment.Result result);
    Page<Payment> findAllByReservationId(UUID reservationId, Pageable page);
}
