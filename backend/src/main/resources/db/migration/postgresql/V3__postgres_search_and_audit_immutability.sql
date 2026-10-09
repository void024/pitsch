-- V3 (PostgreSQL only): trigram search indexes and audit-log immutability.
-- The H2 test database runs the no-op twin in db/migration/h2.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_pitches_company_trgm ON pitches USING gin (lower(company_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_pitches_founder_trgm ON pitches USING gin (lower(founder_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_pitches_sector_trgm  ON pitches USING gin (lower(sector) gin_trgm_ops);

-- Audit events are append-only: UPDATE is always rejected. DELETE is allowed only for the retention job and
-- workspace deletion (both run as the application role and are themselves audited before execution).
CREATE OR REPLACE FUNCTION pitsch_audit_events_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events rows are immutable';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_events_immutable ON audit_events;
CREATE TRIGGER trg_audit_events_immutable
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION pitsch_audit_events_immutable();
