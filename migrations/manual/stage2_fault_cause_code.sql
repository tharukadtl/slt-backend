-- Stage 2 — manual Cause-attachment for Admin/Team Lead (2026-08-27).
--
-- Verified against the live schema before writing: `faults` has 8 rows; `cause_id` already exists
-- (Cause/Material hierarchy import, 2026-08-21, cause_material_hierarchy_import_schema.sql) with a
-- real FK to cause_of_fault(id), but no denormalized display column exists yet — the exact same gap
-- circuit_id had before h1c_fault_circuit_code.sql. Nullable, no backfill needed (existing faults
-- simply get NULL until someone attaches a Cause through the new PATCH /api/faults/{id}/cause
-- endpoint).
ALTER TABLE faults
    ADD COLUMN cause_code VARCHAR(10) NULL AFTER cause_id;
