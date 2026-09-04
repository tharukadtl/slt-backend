-- Cause/Material hierarchy import schema (2026-08-21) — prerequisite for
-- fieldops/scripts/import_cause_material_data.py, which loads
-- docs/master-data/{TYPEOFFAULT,CAUSECATEGORY,CAUSEOFFAULT,MATERIALCATEGORY,
-- MATERIALSUBCATEGORY,MATERIAL,MATERIALCAUSE_,SERVICETYPE}.csv.
--
-- As with every other manual migration here: ddl-auto=update only ADDS
-- missing columns/tables — it never creates a brand-new lookup table
-- referenced only by a new column on an existing entity, so type_of_fault,
-- cause_category, cause_of_fault, and material_cause are hand-written.
--
-- Verified against the live schema before writing (same standing practice
-- as master_data_import_schema.sql): type_of_fault/cause_category/
-- cause_of_fault/material_cause confirmed not to exist yet (SHOW TABLES);
-- material_categories confirmed EMPTY (0 rows); materials confirmed at 2
-- rows (pre-existing app-seeded rows, sku='CAT6-001' and sku='' — neither
-- collides with any real WFMS MATERIALCODE, checked directly); faults
-- confirmed at 8 rows. All ALTERs below are additive/nullable — no backfill
-- risk to the two existing materials rows or the 8 existing faults rows.

-- ─── 1. type_of_fault (TYPEOFFAULT.csv, 13 rows, 1 placeholder DEFXXX) ────
CREATE TABLE type_of_fault (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    type_code   VARCHAR(10)  NOT NULL,
    description VARCHAR(150) NULL,
    sort_key    INT          NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_of_fault_code (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ─── 2. cause_category (CAUSECATEGORY.csv, 85 rows, 1 placeholder DEFXXX) ──
-- FK integrity confirmed clean before writing this migration: all 85 rows'
-- TYPECODE resolve to a real type_of_fault row (see the investigation in
-- QA_Compliance_Consolidated_Report.md, "Cause/Material hierarchy import").
CREATE TABLE cause_category (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    cause_category_code VARCHAR(10)  NOT NULL,
    description         VARCHAR(150) NULL,
    type_of_fault_id    BIGINT       NULL,
    sort_key            INT          NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cause_category_code (cause_category_code),
    CONSTRAINT fk_cause_category_type FOREIGN KEY (type_of_fault_id) REFERENCES type_of_fault (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ─── 3. cause_of_fault (CAUSEOFFAULT.csv, 869 rows, 1 placeholder DEFXXX) ──
-- FK integrity confirmed clean: all 869 rows' CAUSECATEGORYCODE resolve to
-- a real cause_category row. applies_copper/applies_ftth/applies_lte mirror
-- the source's COPPER/FTTH/LTE applicability flags — real signal (e.g.
-- "faulty rosette" applies to copper+ftth, not lte), not dropped on import.
CREATE TABLE cause_of_fault (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    cause_code          VARCHAR(10)  NOT NULL,
    description         VARCHAR(150) NULL,
    cause_category_id   BIGINT       NULL,
    clarity_description VARCHAR(150) NULL,
    applies_copper      BIT(1)       NOT NULL DEFAULT b'0',
    applies_ftth         BIT(1)       NOT NULL DEFAULT b'0',
    applies_lte         BIT(1)       NOT NULL DEFAULT b'0',
    sort_key            INT          NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cause_of_fault_code (cause_code),
    CONSTRAINT fk_cause_of_fault_category FOREIGN KEY (cause_category_id) REFERENCES cause_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ─── 4. material_categories: reuse existing table, add a code column ─────
-- MaterialCategory already supports a parent_id self-reference (Cables ->
-- Ethernet Cables), so MATERIALCATEGORY.csv (9 rows, top-level) and
-- MATERIALSUBCATEGORY.csv (317 rows, children) both land here rather than
-- in new tables. It had no natural-key column at all before this — `code`
-- is added so the import (and any future re-run) can resolve
-- MATERIALCATEGORYCODE/MATERIALSUBCATEGORYCODE by lookup instead of by
-- name-matching free text, and so Material.materialsubcategorycode can be
-- resolved the same way. Nullable + unique: MySQL permits multiple NULLs
-- under a UNIQUE index, so the two pre-existing app rows/any future
-- app-created category without a WFMS code are unaffected.
ALTER TABLE material_categories
    ADD COLUMN code VARCHAR(30) NULL AFTER name,
    ADD UNIQUE KEY uk_material_category_code (code);

-- ─── 5. materials: sku becomes the real MATERIALCODE, richer metadata ────
-- erp_code/erp_description/brand/measurement_code are additive and
-- nullable — the 2 pre-existing app-seeded rows (sku='CAT6-001', sku='')
-- get NULL in all four and are otherwise untouched.
ALTER TABLE materials
    ADD COLUMN erp_code VARCHAR(50) NULL,
    ADD COLUMN erp_description VARCHAR(300) NULL,
    ADD COLUMN brand VARCHAR(100) NULL,
    ADD COLUMN measurement_code VARCHAR(10) NULL;

-- ─── 6. material_cause (MATERIALCAUSE_.csv, 62 rows) ──────────────────────
-- cause_id/material_id are REAL generated ids (cause_of_fault.id /
-- materials.id) resolved by the import script's own row-position logic —
-- see that script's own large warning comment. This table never stores the
-- raw CSV integers (MaterialCause_.CAUSECODE/MATERIALCODE themselves are
-- NOT foreign keys to anything by value — they are 1-indexed row positions
-- into CAUSEOFFAULT.csv/MATERIAL.csv's original export order, confirmed by
-- direct investigation, 0% direct-value match, 100% row-position match
-- across all 62 rows).
CREATE TABLE material_cause (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    cause_id    BIGINT      NOT NULL,
    material_id BIGINT      NOT NULL,
    sort_key    INT         NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_cause_pair (cause_id, material_id),
    CONSTRAINT fk_material_cause_cause    FOREIGN KEY (cause_id)    REFERENCES cause_of_fault (id),
    CONSTRAINT fk_material_cause_material FOREIGN KEY (material_id) REFERENCES materials (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ─── 7. faults: additive causeId, same pattern as circuit_id/nearest_exchange_id ──
-- Purely additive alongside the existing free-text cause_of_fault column
-- (untouched, still written by JobService/FaultService at job completion).
-- Nullable, no backfill: all 8 existing faults rows get NULL until a
-- Technician/the system starts setting it via a structured picker (out of
-- scope for this import — this migration only adds the column).
ALTER TABLE faults
    ADD COLUMN cause_id BIGINT NULL AFTER cause_of_fault,
    ADD CONSTRAINT fk_fault_cause FOREIGN KEY (cause_id) REFERENCES cause_of_fault (id);
