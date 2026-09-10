-- btree_gist is installed by V1. Preserve its historical checksum.
CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES users(id),
    created_by UUID NOT NULL REFERENCES users(id),
    room_id UUID NOT NULL REFERENCES rooms(id),
    check_in DATE NOT NULL,
    check_out DATE NOT NULL,
    guests INTEGER NOT NULL CHECK (guests > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('CONFIRMED','CHECKED_IN','CHECKED_OUT','CANCELLED','NO_SHOW')),
    agreed_nightly_rate NUMERIC(12,2) NOT NULL CHECK (agreed_nightly_rate > 0),
    total_amount NUMERIC(12,2) NOT NULL CHECK (total_amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    cancellation_reason VARCHAR(500),
    cancelled_by UUID REFERENCES users(id),
    cancelled_at TIMESTAMPTZ,
    checked_in_at TIMESTAMPTZ,
    checked_out_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (check_in < check_out),
    CHECK (total_amount = agreed_nightly_rate * (check_out - check_in)),
    CHECK (status <> 'CANCELLED' OR (cancelled_by IS NOT NULL AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL AND length(trim(cancellation_reason)) > 0)),
    CONSTRAINT ex_reservations_room_stay EXCLUDE USING gist (
        room_id WITH =,
        daterange(check_in, check_out, '[)') WITH &&
    ) WHERE (status IN ('CONFIRMED','CHECKED_IN','CHECKED_OUT'))
);
CREATE INDEX idx_reservations_customer_date ON reservations(customer_id, check_in, id);
CREATE INDEX idx_reservations_status_date ON reservations(status, check_in, id);
CREATE INDEX idx_reservations_room_checkout ON reservations(room_id, check_out);
CREATE INDEX idx_reservations_created_by ON reservations(created_by);

CREATE TABLE idempotency_requests (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id),
    operation VARCHAR(40) NOT NULL CHECK (operation IN ('CREATE_RESERVATION','STAFF_CREATE_RESERVATION')),
    request_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    reservation_id UUID REFERENCES reservations(id),
    response_json TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    CHECK ((reservation_id IS NULL) = (response_json IS NULL)),
    CONSTRAINT uq_idempotency_actor_operation_key UNIQUE(actor_id, operation, request_key)
);
-- Expired receipts remain tombstones: keys are never silently reused.
CREATE INDEX idx_idempotency_expiration ON idempotency_requests(expires_at);
