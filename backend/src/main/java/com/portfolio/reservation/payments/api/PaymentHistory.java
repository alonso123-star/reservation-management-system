package com.portfolio.reservation.payments.api;

import com.portfolio.reservation.shared.api.PageView;
import java.math.BigDecimal;

/** Settlement is derived from payments/refunds; it is not a Reservation state. */
public record PaymentHistory(Settlement settlement, BigDecimal amountDue, String currency, boolean canPay,
        PageView<PaymentView> attempts) {
    public enum Settlement { UNPAID, PAID, REFUNDED }
}
