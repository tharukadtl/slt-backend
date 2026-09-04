-- Manual schema change (this project has no Flyway/Liquibase; spring.jpa.hibernate.ddl-auto=update
-- only ADDS missing columns/tables — it never renames or drops anything, and never backfills a new
-- NOT NULL column from an old one). Renames Branch -> Opmc (Stage B of the OPMC restructure) at the
-- database level to match the already-renamed Java code.
--
-- IMPORTANT — this version was rewritten after running it for real against the local dev DB
-- (2026-08-15) and discovering the naive "just RENAME everything" version (this file's first draft)
-- does not apply cleanly. The renamed Java code had already been run once (a @SpringBootTest context
-- start), so ddl-auto had already ADDED the new `opmcs` table and every new `opmc_id`/`opmc_name`
-- column ALONGSIDE the old `branches` table and `branch_id`/`branch_name` columns — not instead of
-- them. Worse: for the two NOT NULL columns (faults.opmc_id, payments.opmc_id), MySQL's ALTER TABLE
-- ADD COLUMN silently back-filled every existing row with 0 (verified directly: every faults/payments
-- row had opmc_id=0 while branch_id correctly held 1) — a real, distinct data-integrity trap, not
-- just a naming inconvenience. RENAME COLUMN and RENAME TABLE both fail outright once the target name
-- already exists, which is what actually happened first. This version handles that real state:
-- copy the correct value across, THEN drop the old column, rather than renaming — safe whether the
-- new column is empty, NULL, or (as with faults/payments) silently wrong.
--
-- Requires MySQL 8.0.7+ (RENAME COLUMN syntax used for the one case where no column collision
-- exists). Preserves all existing data — verified against real rows in this database, not assumed.
--
-- Scope: only the real Branch relationship is renamed here (branchId -> opmcId, the `branches`
-- table itself, and its dependents), per Stage B. KpiTarget's separate, unrelated `kpi_branch_id`
-- column (Stage A) is deliberately NOT touched anywhere below.
--
-- New Stage C tables (work_groups, exchanges, cabs, dps, circuits) needed no migration — they were
-- created fresh by ddl-auto with the correct `opmc_id` column name from the start, since there was
-- no old table for them to collide with.

-- ─── 0. Drop the empty, auto-created `opmcs` table before renaming `branches` onto that name ─────
-- Confirmed empty (0 rows) before running — this is the placeholder ddl-auto created when it saw
-- the renamed entity as a brand-new table, not the real data (still sitting in `branches`).
--
-- Three real FK constraints (Stage C's exchanges/work_groups + KpiTarget's own `opmc` relationship)
-- already point at this placeholder table, since ddl-auto also created those constraints when it
-- built the tables/columns. Drop the FKs first (constraint names confirmed live via
-- information_schema.KEY_COLUMN_USAGE, not guessed) — ddl-auto re-adds all three automatically on
-- the next Spring Boot startup once `opmcs` is the real, renamed table, so nothing needs to be
-- manually recreated. exchanges/work_groups were both confirmed empty (0 rows) before this ran.
ALTER TABLE exchanges    DROP FOREIGN KEY FKteisv718g1xa9wsx2px215an;
ALTER TABLE kpi_targets  DROP FOREIGN KEY FKl1jsolekmlmes5ywkmahi9uvg;
ALTER TABLE work_groups  DROP FOREIGN KEY FKdbhnaybhdj2up5hexmjrepclb;

DROP TABLE IF EXISTS opmcs;

-- ─── 1. The Branch entity itself ───────────────────────────────────────────────────────────────

RENAME TABLE branches TO opmcs;

ALTER TABLE opmcs
    RENAME COLUMN branch_type TO opmc_type;

-- province: free-text VARCHAR -> fixed 9-value enum (Stage C item 1). Normalize BEFORE changing the
-- column type, since MODIFY COLUMN ... ENUM(...) rejects any value not already in the list.
UPDATE opmcs
SET province = UPPER(REPLACE(TRIM(province), ' ', '_'))
WHERE province IS NOT NULL;

UPDATE opmcs SET province = 'WESTERN'        WHERE province IN ('WESTERN_PROVINCE', 'WP');
UPDATE opmcs SET province = 'CENTRAL'        WHERE province IN ('CENTRAL_PROVINCE', 'CP');
UPDATE opmcs SET province = 'SOUTHERN'       WHERE province IN ('SOUTHERN_PROVINCE', 'SP');
UPDATE opmcs SET province = 'NORTHERN'       WHERE province IN ('NORTHERN_PROVINCE', 'NP');
UPDATE opmcs SET province = 'EASTERN'        WHERE province IN ('EASTERN_PROVINCE', 'EP');
UPDATE opmcs SET province = 'NORTH_WESTERN'  WHERE province IN ('NORTH_WESTERN_PROVINCE', 'NORTH-WESTERN', 'NWP', 'WAYAMBA');
UPDATE opmcs SET province = 'NORTH_CENTRAL'  WHERE province IN ('NORTH_CENTRAL_PROVINCE', 'NORTH-CENTRAL', 'NCP', 'RAJARATA');
UPDATE opmcs SET province = 'UVA'            WHERE province IN ('UVA_PROVINCE');
UPDATE opmcs SET province = 'SABARAGAMUWA'   WHERE province IN ('SABARAGAMUWA_PROVINCE', 'SG');

-- Safety net: anything still unrecognized becomes NULL (a missing province, not a crash on read) —
-- re-check with the SELECT below first if you'd rather fix values by hand than lose them to NULL.
--   SELECT id, name, province FROM opmcs
--   WHERE province IS NOT NULL AND province NOT IN
--     ('WESTERN','CENTRAL','SOUTHERN','NORTHERN','EASTERN',
--      'NORTH_WESTERN','NORTH_CENTRAL','UVA','SABARAGAMUWA');
UPDATE opmcs
SET province = NULL
WHERE province IS NOT NULL
  AND province NOT IN (
    'WESTERN','CENTRAL','SOUTHERN','NORTHERN','EASTERN',
    'NORTH_WESTERN','NORTH_CENTRAL','UVA','SABARAGAMUWA'
  );

ALTER TABLE opmcs
    MODIFY COLUMN province ENUM(
        'WESTERN','CENTRAL','SOUTHERN','NORTHERN','EASTERN',
        'NORTH_WESTERN','NORTH_CENTRAL','UVA','SABARAGAMUWA'
    ) DEFAULT NULL;

-- ─── 2. Every dependent table: copy the real value across, then drop the old column ───────────────
-- Copy-then-drop, not RENAME COLUMN — ddl-auto had already added opmc_id/opmc_name next to the old
-- columns (see note above), including silently defaulting faults.opmc_id/payments.opmc_id to 0, so
-- the "new" column cannot be trusted as-is and RENAME COLUMN would fail on the name collision anyway.
--
-- Six of these nine tables also turned out to have a real, hand-written FK constraint on branch_id
-- (fk_user_branch, fk_veh_branch, fk_mr_branch, fk_pay_branch, fk_fault_branch, fk_kt_branch — names
-- confirmed live via information_schema.KEY_COLUMN_USAGE, not guessed) — a straight DROP COLUMN
-- fails on those with "needed in a foreign key constraint" until the FK is dropped first. materials,
-- kpi_scores and confirmed_resource_plans have no such FK (branch_id there is a plain indexed
-- column), so no DROP FOREIGN KEY is needed for those three. Any index defined solely on the dropped
-- column (idx_*_branch, idx_material_branch, idx_kpi_score_branch, idx_kpi_target_branch,
-- uk_confirmed_plan_branch_date_shift, etc.) is dropped automatically by MySQL along with the column
-- — no separate DROP INDEX needed either way. The matching new opmc_id-based indexes ddl-auto
-- already created (idx_material_opmc, idx_kpi_score_opmc, idx_kpi_target_opmc,
-- uk_confirmed_plan_opmc_date_shift) are correct as they stand.
--
-- (There is also an unrelated, pre-existing `workgroups` table — no underscore, distinct from Stage
-- C's new `work_groups` — with its own `fk_wg_branch` FK on branch_id. No current entity maps to it;
-- left untouched entirely, not part of this rename's scope.)

ALTER TABLE users DROP FOREIGN KEY fk_user_branch;
UPDATE users SET opmc_id = branch_id, opmc_name = branch_name;
ALTER TABLE users DROP COLUMN branch_id;
ALTER TABLE users DROP COLUMN branch_name;

ALTER TABLE vehicles DROP FOREIGN KEY fk_veh_branch;
UPDATE vehicles SET opmc_id = branch_id;
ALTER TABLE vehicles DROP COLUMN branch_id;

UPDATE materials SET opmc_id = branch_id;
ALTER TABLE materials DROP COLUMN branch_id;

ALTER TABLE material_requests DROP FOREIGN KEY fk_mr_branch;
UPDATE material_requests SET opmc_id = branch_id;
ALTER TABLE material_requests DROP COLUMN branch_id;

ALTER TABLE payments DROP FOREIGN KEY fk_pay_branch;
UPDATE payments SET opmc_id = branch_id;
ALTER TABLE payments DROP COLUMN branch_id;

ALTER TABLE faults DROP FOREIGN KEY fk_fault_branch;
UPDATE faults SET opmc_id = branch_id;
ALTER TABLE faults DROP COLUMN branch_id;

UPDATE kpi_scores SET opmc_id = branch_id;
ALTER TABLE kpi_scores DROP COLUMN branch_id;

UPDATE confirmed_resource_plans SET opmc_id = branch_id;
ALTER TABLE confirmed_resource_plans DROP COLUMN branch_id;

-- The above DROP COLUMN does NOT drop uk_confirmed_plan_branch_date_shift — verified live: MySQL
-- narrows a composite unique key when only one of its columns is dropped, rather than removing it,
-- so it silently kept enforcing uniqueness on the two remaining columns (plan_date, shift) alone,
-- under its old name. That is actively wrong here — it would block two DIFFERENT OPMCs from ever
-- having a confirmed plan for the same date+shift, which the real uk_confirmed_plan_opmc_date_shift
-- (opmc_id, plan_date, shift) — already present, ddl-auto-created — correctly allows. Confirmed via
-- SHOW INDEX this is the only composite index touched by this migration that had this happen (every
-- other branch_id index dropped above was single-column, so DROP COLUMN removed it outright).
ALTER TABLE confirmed_resource_plans DROP INDEX uk_confirmed_plan_branch_date_shift;

-- kpi_targets has TWO branch-shaped columns — only branch_id (the real Branch/Opmc FK,
-- KpiTarget.java's `branch`/now `opmc` field) is touched here. kpi_branch_id (the loose,
-- unrelated "monthly target scope" column, Stage A's `monthlyTargetBranchId`) is left alone.
ALTER TABLE kpi_targets DROP FOREIGN KEY fk_kt_branch;
UPDATE kpi_targets SET opmc_id = branch_id;
ALTER TABLE kpi_targets DROP COLUMN branch_id;
