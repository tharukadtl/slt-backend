-- H1b — auto-derive nearest Exchange from a Fault's GPS (2026-08-20).
--
-- Verified against the live schema before writing, same standing practice as every other manual
-- migration here: `faults` has 8 rows, and both new columns are nullable with no default-needed
-- backfill (existing faults simply get NULL, meaning "not derived" -- correct, since this is only
-- computed going forward at Fault creation, not retroactively).
ALTER TABLE faults
    ADD COLUMN nearest_exchange_id BIGINT NULL AFTER circuit_id,
    ADD COLUMN nearest_exchange_distance_km DOUBLE NULL AFTER nearest_exchange_id;
