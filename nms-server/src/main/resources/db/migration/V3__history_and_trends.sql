-- ---------------------------------------------------------------------------
-- History and trends: the time-series side of the platform.
--
-- These tables take every collected value, so they dwarf everything in V1/V2
-- by several orders of magnitude. They are kept deliberately narrow -- no
-- foreign keys, no text columns on the numeric paths -- because an index entry
-- or a constraint check here is paid on every single insert.
--
-- TimescaleDB is used when available: hypertables give transparent time
-- partitioning, native compression and cheap retention drops. When the
-- extension is absent the same tables work as plain PostgreSQL tables and the
-- housekeeper falls back to batched deletes. Nothing in the application layer
-- depends on which mode is active.
-- ---------------------------------------------------------------------------

DO $$
BEGIN
    -- Managed Postgres offerings differ in whether this extension is present
    -- and whether the connecting role may create it. Neither is fatal.
    CREATE EXTENSION IF NOT EXISTS timescaledb;
    RAISE NOTICE 'TimescaleDB enabled: history tables will be hypertables';
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'TimescaleDB unavailable (%), using plain tables', SQLERRM;
END
$$;

-- ---------------------------------------------------------------------------
-- Raw history, one table per value type
-- ---------------------------------------------------------------------------

CREATE TABLE history_uint (
    item_id  BIGINT      NOT NULL,
    clock    TIMESTAMPTZ NOT NULL,
    value    BIGINT      NOT NULL
);

CREATE TABLE history_float (
    item_id  BIGINT           NOT NULL,
    clock    TIMESTAMPTZ      NOT NULL,
    value    DOUBLE PRECISION NOT NULL
);

CREATE TABLE history_str (
    item_id  BIGINT       NOT NULL,
    clock    TIMESTAMPTZ  NOT NULL,
    value    VARCHAR(255) NOT NULL
);

CREATE TABLE history_text (
    item_id  BIGINT      NOT NULL,
    clock    TIMESTAMPTZ NOT NULL,
    value    TEXT        NOT NULL
);

CREATE TABLE history_log (
    item_id      BIGINT      NOT NULL,
    clock        TIMESTAMPTZ NOT NULL,
    -- Timestamp parsed out of the log line itself, which is often not the
    -- time we collected it. Both are needed to investigate an incident.
    source_clock TIMESTAMPTZ,
    source       VARCHAR(255) NOT NULL DEFAULT '',
    severity     INTEGER      NOT NULL DEFAULT 0,
    log_event_id INTEGER      NOT NULL DEFAULT 0,
    value        TEXT         NOT NULL
);

-- ---------------------------------------------------------------------------
-- Trends: hourly rollups of numeric history.
--
-- Trends are what make a year-long graph possible without reading a year of
-- raw samples. They are computed by the server rather than by a database-
-- specific continuous aggregate so the behaviour is identical in both modes.
-- ---------------------------------------------------------------------------

CREATE TABLE trends_uint (
    item_id    BIGINT      NOT NULL,
    clock      TIMESTAMPTZ NOT NULL,   -- truncated to the hour
    num        INTEGER     NOT NULL,   -- samples in the bucket
    value_min  BIGINT      NOT NULL,
    value_avg  BIGINT      NOT NULL,
    value_max  BIGINT      NOT NULL
);

CREATE TABLE trends_float (
    item_id    BIGINT           NOT NULL,
    clock      TIMESTAMPTZ      NOT NULL,
    num        INTEGER          NOT NULL,
    value_min  DOUBLE PRECISION NOT NULL,
    value_avg  DOUBLE PRECISION NOT NULL,
    value_max  DOUBLE PRECISION NOT NULL
);

-- ---------------------------------------------------------------------------
-- Indexes.
--
-- Every read is "one item (or a few), over a time range, newest first", so a
-- single composite index per table serves both the graph and latest-data
-- paths. On hypertables these are created per chunk automatically.
-- ---------------------------------------------------------------------------

CREATE UNIQUE INDEX ux_history_uint  ON history_uint  (item_id, clock DESC);
CREATE UNIQUE INDEX ux_history_float ON history_float (item_id, clock DESC);
CREATE INDEX        ix_history_str   ON history_str   (item_id, clock DESC);
CREATE INDEX        ix_history_text  ON history_text  (item_id, clock DESC);
CREATE INDEX        ix_history_log   ON history_log   (item_id, clock DESC);
CREATE UNIQUE INDEX ux_trends_uint   ON trends_uint   (item_id, clock DESC);
CREATE UNIQUE INDEX ux_trends_float  ON trends_float  (item_id, clock DESC);

-- ---------------------------------------------------------------------------
-- Convert to hypertables when TimescaleDB is present.
--
-- Chunk sizing is a trade-off: small chunks make retention drops granular but
-- multiply planning cost on wide range scans. One day for raw history and one
-- month for trends matches the access pattern -- history is queried in hours,
-- trends in months.
-- ---------------------------------------------------------------------------

DO $$
DECLARE
    ts_installed BOOLEAN;
    tbl          TEXT;
    history_tables TEXT[] := ARRAY['history_uint', 'history_float', 'history_str',
                                   'history_text', 'history_log'];
    trend_tables   TEXT[] := ARRAY['trends_uint', 'trends_float'];
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'timescaledb'
    ) INTO ts_installed;

    IF NOT ts_installed THEN
        RAISE NOTICE 'Skipping hypertable conversion; housekeeper will use batched deletes';
        RETURN;
    END IF;

    FOREACH tbl IN ARRAY history_tables LOOP
        EXECUTE format(
            'SELECT create_hypertable(%L, ''clock'', chunk_time_interval => INTERVAL ''1 day'', migrate_data => TRUE)',
            tbl);
    END LOOP;

    FOREACH tbl IN ARRAY trend_tables LOOP
        EXECUTE format(
            'SELECT create_hypertable(%L, ''clock'', chunk_time_interval => INTERVAL ''30 days'', migrate_data => TRUE)',
            tbl);
    END LOOP;

    -- Compression. Segmenting by item_id groups each item's samples together
    -- inside a chunk, which is what lets the columnar encoding reach the
    -- 10-20x ratios that make long retention affordable.
    FOREACH tbl IN ARRAY history_tables || trend_tables LOOP
        BEGIN
            EXECUTE format(
                'ALTER TABLE %I SET (timescaledb.compress, '
                'timescaledb.compress_segmentby = ''item_id'', '
                'timescaledb.compress_orderby = ''clock DESC'')', tbl);
        EXCEPTION
            WHEN OTHERS THEN
                RAISE NOTICE 'Compression unavailable for % (%)', tbl, SQLERRM;
        END;
    END LOOP;

    -- Compress raw history after 7 days: recent data stays uncompressed so
    -- trigger evaluation and live graphs are never slowed by decompression.
    FOREACH tbl IN ARRAY history_tables LOOP
        BEGIN
            EXECUTE format('SELECT add_compression_policy(%L, INTERVAL ''7 days'')', tbl);
        EXCEPTION
            WHEN OTHERS THEN
                RAISE NOTICE 'Compression policy not added for % (%)', tbl, SQLERRM;
        END;
    END LOOP;

    FOREACH tbl IN ARRAY trend_tables LOOP
        BEGIN
            EXECUTE format('SELECT add_compression_policy(%L, INTERVAL ''90 days'')', tbl);
        EXCEPTION
            WHEN OTHERS THEN
                RAISE NOTICE 'Compression policy not added for % (%)', tbl, SQLERRM;
        END;
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------------
-- Latest value cache.
--
-- "What is this item's current value?" is the single most frequent read in the
-- product -- every dashboard tile, every host row, the whole camera wall. It
-- is answered from this narrow table instead of an ORDER BY over history,
-- which on a compressed chunk would be dramatically more expensive.
-- ---------------------------------------------------------------------------

CREATE TABLE item_latest (
    item_id      BIGINT      PRIMARY KEY REFERENCES item(item_id) ON DELETE CASCADE,
    clock        TIMESTAMPTZ NOT NULL,
    value_num    DOUBLE PRECISION,
    value_str    TEXT,
    -- Previous value, kept so change()/diff() trigger functions and delta
    -- preprocessing do not need a history read on the hot path.
    prev_clock   TIMESTAMPTZ,
    prev_value_num DOUBLE PRECISION,
    state        VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    error        TEXT        NOT NULL DEFAULT ''
);

CREATE INDEX ix_item_latest_clock ON item_latest (clock DESC);

-- ---------------------------------------------------------------------------
-- Availability rollup for cameras and other reachability-first devices.
--
-- Uptime reporting from raw history means scanning every ping sample in the
-- period. This table records state *transitions* only, so "which cameras were
-- offline last month and for how long" is a range scan over a handful of rows
-- per device.
-- ---------------------------------------------------------------------------

CREATE TABLE availability_span (
    span_id     BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    host_id     BIGINT      NOT NULL REFERENCES host(host_id) ON DELETE CASCADE,
    item_id     BIGINT      REFERENCES item(item_id) ON DELETE SET NULL,
    state       VARCHAR(16) NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    -- NULL while the span is still current; exactly one open span per host.
    ended_at    TIMESTAMPTZ,
    reason      TEXT        NOT NULL DEFAULT '',
    CONSTRAINT ck_availability_state CHECK (state IN ('UP', 'DOWN', 'UNKNOWN', 'MAINTENANCE'))
);

CREATE INDEX ix_availability_host ON availability_span (host_id, started_at DESC);
CREATE UNIQUE INDEX ux_availability_open ON availability_span (host_id) WHERE ended_at IS NULL;
