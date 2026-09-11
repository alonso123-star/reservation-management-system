CREATE TABLE payments (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL REFERENCES reservations(id),
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    result VARCHAR(16) NOT NULL CHECK (result IN ('APPROVED','DECLINED')),
    simulated_reference VARCHAR(50) NOT NULL UNIQUE,
    actor_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_payments_approved_reservation ON payments(reservation_id) WHERE result='APPROVED';
CREATE INDEX idx_payments_reservation_created ON payments(reservation_id, created_at, id);

CREATE TABLE refunds (
    id UUID PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id),
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    reason VARCHAR(500) NOT NULL CHECK (length(trim(reason)) > 0),
    actor_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_refunds_payment UNIQUE(payment_id)
);

-- Extend the existing receipt store; all V4 reservation receipts remain valid.
ALTER TABLE idempotency_requests DROP CONSTRAINT idempotency_requests_operation_check;
ALTER TABLE idempotency_requests ADD CONSTRAINT idempotency_requests_operation_check
    CHECK (operation IN ('CREATE_RESERVATION','STAFF_CREATE_RESERVATION','PAY_RESERVATION'));
ALTER TABLE idempotency_requests ADD COLUMN payment_id UUID REFERENCES payments(id);
ALTER TABLE idempotency_requests ADD CONSTRAINT ck_idempotency_payment_result CHECK (
    (operation='PAY_RESERVATION' AND ((payment_id IS NULL) = (response_json IS NULL)))
    OR (operation<>'PAY_RESERVATION' AND payment_id IS NULL)
);
