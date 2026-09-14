-- ---------------------------------------------------------------------------
-- Durable storage for the previous *raw* value of rate-based items.
--
-- A change-per-second step needs the value before preprocessing, but
-- item_latest holds the value after it -- for an interface counter those are
-- entirely different numbers (a cumulative octet count versus a throughput
-- rate), so the rate cannot be recomputed from what is stored.
--
-- Holding it only in memory would be wrong in two ways that both matter here.
-- Server instances claim items with SKIP LOCKED, so consecutive polls of the
-- same item can land on different instances; and a restart would lose every
-- item's baseline at once. Either way the next sample is discarded for want of
-- a predecessor, which on a fleet of switches means visible gaps in every
-- traffic graph.
--
-- Only items with a rate or change step ever write these columns, so the cost
-- falls on SNMP counters rather than on every value collected.
-- ---------------------------------------------------------------------------

ALTER TABLE item_latest
    ADD COLUMN raw_value_num DOUBLE PRECISION,
    ADD COLUMN raw_clock     TIMESTAMPTZ;

COMMENT ON COLUMN item_latest.raw_value_num IS
    'Value before preprocessing, kept as the baseline for change and rate steps';
COMMENT ON COLUMN item_latest.raw_clock IS
    'Collection time of raw_value_num, used as the denominator for per-second rates';
