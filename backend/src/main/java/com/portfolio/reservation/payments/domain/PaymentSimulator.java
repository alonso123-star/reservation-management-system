package com.portfolio.reservation.payments.domain;

/** Local simulation boundary. A future provider must keep its integration inside payments. */
public interface PaymentSimulator {
    Payment.Result evaluate(long previousAttempts);
}
