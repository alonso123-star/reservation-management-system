-- V2 prepared audit metadata but did not include the planned safe change payload.
-- Historical events have no change details. No backfill of credentials or personal data.
ALTER TABLE audit_events ADD COLUMN changes JSONB NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(changes) = 'object');
