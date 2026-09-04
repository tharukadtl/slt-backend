-- H1a — Geocode Exchange + Opmc (2026-08-20). Opmc already has latitude/longitude (nullable,
-- unpopulated for the 65 real rows). Exchange has neither column yet — add them here so
-- fieldops/scripts/geocode_master_data.py has somewhere to write real coordinates.
--
-- Verified against the live schema before writing, same standing practice as every other manual
-- migration here: `exchanges` has 377 real rows already (the master-data import), so this is a
-- pure ADD COLUMN with no backfill risk (both new columns nullable, no NOT NULL zero-fill trap
-- like opmc_rename.sql hit) — ddl-auto=update would also add these automatically on next boot,
-- but running it directly here means the geocoding script doesn't need to wait for an app restart.
ALTER TABLE exchanges
    ADD COLUMN latitude  DOUBLE NULL AFTER opmc_id,
    ADD COLUMN longitude DOUBLE NULL AFTER latitude;
