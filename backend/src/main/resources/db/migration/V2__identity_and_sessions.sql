CREATE TABLE roles (
    id UUID PRIMARY KEY,
    name VARCHAR(16) NOT NULL UNIQUE CHECK (name IN ('CLIENTE', 'EMPLEADO', 'ADMIN'))
);
INSERT INTO roles (id, name) VALUES
    ('00000000-0000-0000-0000-000000000001', 'CLIENTE'),
    ('00000000-0000-0000-0000-000000000002', 'EMPLEADO'),
    ('00000000-0000-0000-0000-000000000003', 'ADMIN');

CREATE TABLE users (
    id UUID PRIMARY KEY,
    role_id UUID NOT NULL REFERENCES roles(id),
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email) AND email = trim(email)),
    password_hash VARCHAR(100) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    security_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE refresh_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    security_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_sessions_user ON refresh_sessions(user_id);

-- Keep consumed hashes until the family expires to detect replay.
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES refresh_sessions(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_refresh_tokens_session ON refresh_tokens(session_id);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    actor_id UUID REFERENCES users(id),
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    resource_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    request_id UUID NOT NULL
);
CREATE INDEX idx_audit_occurred ON audit_events(occurred_at);
CREATE INDEX idx_audit_actor ON audit_events(actor_id, occurred_at);

-- Shared atomic rate limits, surviving rollbacks and multiple backend instances.
CREATE TABLE auth_rate_limits (
    bucket_hash VARCHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0)
);
