CREATE TABLE room_types (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0 AND name = trim(name)),
    description VARCHAR(2000) NOT NULL,
    capacity INTEGER NOT NULL CHECK (capacity BETWEEN 1 AND 1000),
    base_price NUMERIC(12,2) NOT NULL CHECK (base_price > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_room_types_name ON room_types(lower(name));
CREATE INDEX idx_room_types_catalog ON room_types(active, capacity, id);

CREATE TABLE rooms (
    id UUID PRIMARY KEY,
    room_type_id UUID NOT NULL REFERENCES room_types(id),
    code VARCHAR(30) NOT NULL CHECK (length(trim(code)) > 0 AND code = upper(trim(code))),
    floor INTEGER NOT NULL CHECK (floor BETWEEN -5 AND 200),
    operational_status VARCHAR(20) NOT NULL CHECK (operational_status IN ('ACTIVE','MAINTENANCE','OUT_OF_SERVICE')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_rooms_code UNIQUE (code)
);
CREATE INDEX idx_rooms_type ON rooms(room_type_id);
CREATE INDEX idx_rooms_catalog ON rooms(active, operational_status, floor, id);
