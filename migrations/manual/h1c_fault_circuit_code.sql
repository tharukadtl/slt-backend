-- H1c — manual Circuit-attachment for Admin/Team Lead (2026-08-21).
--
-- Verified against the live schema before writing: `faults` has 8 rows, nullable column, no
-- backfill needed (existing faults simply get NULL until someone attaches a Circuit through the
-- new PATCH /api/faults/{id}/circuit endpoint). `circuit_id` already exists (Stage C) and is
-- untouched by this migration.
ALTER TABLE faults
    ADD COLUMN circuit_code VARCHAR(30) NULL AFTER circuit_id;
