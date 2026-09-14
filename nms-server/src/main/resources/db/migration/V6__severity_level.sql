-- ---------------------------------------------------------------------------
-- Numeric severity, for comparison and ordering.
--
-- Severity is stored as a name because a name is readable in the database and
-- survives the enum being reordered. But a name cannot be *compared*: asking
-- for "High and above" became the string comparison
-- 'HIGH' >= 'NOT_CLASSIFIED', which is false, while
-- 'WARNING' >= 'NOT_CLASSIFIED' is true. The filter returned warnings and hid
-- disasters -- the exact inverse of what it was asked, on the control operators
-- use most. ORDER BY severity DESC had the same fault, sorting Warning above
-- Disaster alphabetically.
--
-- The column is GENERATED rather than maintained by the application. A
-- denormalised value that application code has to remember to update is a value
-- that eventually drifts -- an import script, a data fix, a migration written in
-- a hurry -- and a severity filter that is quietly wrong is worse than one that
-- is obviously broken. Postgres computes it from the name on every write, so it
-- cannot disagree with the column it is derived from.
-- ---------------------------------------------------------------------------

ALTER TABLE problem ADD COLUMN severity_level SMALLINT
    GENERATED ALWAYS AS (CASE severity
        WHEN 'NOT_CLASSIFIED' THEN 0
        WHEN 'INFORMATION'    THEN 1
        WHEN 'WARNING'        THEN 2
        WHEN 'AVERAGE'        THEN 3
        WHEN 'HIGH'           THEN 4
        WHEN 'DISASTER'       THEN 5
        ELSE 0
    END) STORED;

ALTER TABLE event ADD COLUMN severity_level SMALLINT
    GENERATED ALWAYS AS (CASE severity
        WHEN 'NOT_CLASSIFIED' THEN 0
        WHEN 'INFORMATION'    THEN 1
        WHEN 'WARNING'        THEN 2
        WHEN 'AVERAGE'        THEN 3
        WHEN 'HIGH'           THEN 4
        WHEN 'DISASTER'       THEN 5
        ELSE 0
    END) STORED;

-- The open-problems index is rebuilt on the numeric column, since that is what
-- the filter and the sort now use.
DROP INDEX IF EXISTS ix_problem_open;
CREATE INDEX ix_problem_open ON problem (tenant_id, severity_level DESC, clock DESC)
    WHERE r_event_id IS NULL;

CREATE INDEX ix_event_severity ON event (tenant_id, severity_level DESC, clock DESC);

COMMENT ON COLUMN problem.severity_level IS
    'Derived from severity: 0 not classified .. 5 disaster. Generated, never written directly.';
COMMENT ON COLUMN event.severity_level IS
    'Derived from severity: 0 not classified .. 5 disaster. Generated, never written directly.';
