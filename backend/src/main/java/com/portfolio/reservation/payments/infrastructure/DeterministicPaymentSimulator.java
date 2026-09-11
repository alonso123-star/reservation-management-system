package com.portfolio.reservation.payments.infrastructure;

import com.portfolio.reservation.payments.domain.*;
import org.springframework.stereotype.Component;

/** Synthetic scenario: first new attempt declines, following attempt approves; no client result input. */
@Component
public class DeterministicPaymentSimulator implements PaymentSimulator {
    @Override public Payment.Result evaluate(long previousAttempts) {
        if (previousAttempts < 0) throw new IllegalArgumentException("Attempt count cannot be negative");
        return previousAttempts == 0 ? Payment.Result.DECLINED : Payment.Result.APPROVED;
    }
}
