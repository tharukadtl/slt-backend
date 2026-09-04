-- Master-data CSV import schema changes (2026-08-20) — prerequisite for
-- fieldops/scripts/import_master_data.py, which loads docs/master-data/
-- (Opmc -> Exchange -> CircuitCategory -> Cab/Dp/Circuit derived from
-- CIRCUIT.csv) into the Stage C cabs/dps/circuits tables ddl-auto created
-- empty. As with every other manual migration here: ddl-auto=update only
-- ADDS missing columns/tables — it never relaxes NOT NULL, never drops or
-- widens a unique constraint, and never creates a brand-new lookup table
-- referenced only by a new column on an existing entity. All three are
-- needed here, so this is hand-written.
--
-- Verified against the live schema before writing (same standing practice
-- as opmc_rename.sql/stage_d_workgroup_assignment.sql): exchanges/cabs/dps/
-- circuits confirmed EMPTY (0 rows each) via direct SELECT COUNT(*) on
-- 2026-08-20, so every change below is a pure DDL change with no backfill
-- risk — nothing to lose, nothing that could be silently zero-filled the
-- way opmc_rename.sql's NOT NULL columns were.
--
-- Why the two unique-key changes are required, not stylistic: cabs.code and
-- dps.code were each globally UNIQUE. Real CAB codes (CIRCUITNAME in
-- CIRCUIT.csv) repeat across different Exchanges (confirmed: 76 of 12,876
-- distinct Exchange+CIRCUITNAME pairs share a CIRCUITNAME with another
-- Exchange), and real DP codes ("U009", "C001", etc.) repeat massively
-- across different Cabs (avg 27 DPs/Cab, ~349K rows over only a few hundred
-- distinct DP code strings) — a global unique constraint on either column
-- would reject the overwhelming majority of real Dp inserts and a real
-- slice of Cab inserts. Scoping each to its parent (Exchange for Cab, Cab
-- for Dp) is the correct real-world model, not a workaround.

-- ─── 1. cabs: composite-unique per Exchange, exchange_id nullable ────────
-- Nullable exchange_id: 104 distinct EXCHANGECODE values in CIRCUIT.csv
-- (2,243 rows) have no matching EXCHANGE.csv row — a logged, open
-- master-data-export data-quality gap (see
-- QA_Compliance_Consolidated_Report.md, "Master-data CSV import
-- investigation", 2026-08-20). Importing those Cabs with a NULL Exchange
-- FK keeps the row instead of dropping it or inventing a placeholder
-- Exchange, per the confirmed decision.
ALTER TABLE cabs
    DROP INDEX UK_98pdk513kwpntl9t67326shjd,
    ADD UNIQUE KEY uk_cab_exchange_code (exchange_id, code),
    MODIFY COLUMN exchange_id BIGINT NULL;

-- ─── 2. dps: composite-unique per Cab ─────────────────────────────────────
ALTER TABLE dps
    DROP INDEX UK_kqypamatig81dx2337sb8r04t,
    ADD UNIQUE KEY uk_dp_cab_code (cab_id, code);

-- ─── 3. circuit_categories: new independent lookup (CIRCUITCATEGORY.csv) ──
-- id is NOT auto-increment — it is the literal CIRCUITCATCODE domain value
-- (1=CAB, 2=MSAN, 3=DFA) from the source, so Circuit rows FK to it directly
-- with no separate id-resolution step at import time. Seeded by
-- import_master_data.py from CIRCUITCATEGORY.csv itself (3 rows), not
-- hardcoded here, so the data stays CSV-driven and re-runnable.
CREATE TABLE circuit_categories (
    id          INT          NOT NULL,
    code        VARCHAR(30)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    is_active   BIT(1)       NOT NULL DEFAULT b'1',
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_circuit_category_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ─── 4. circuits: dp_id nullable, new circuit_category_id FK ─────────────
-- Nullable dp_id: 12 of 349,180 CIRCUIT.csv rows have DP="DEFXXX" (a
-- placeholder, not a real DP) — imported with dp_id NULL rather than a
-- fake Dp row, per the confirmed decision.
-- circuit_category_id nullable: 35 of 349,180 rows have a blank
-- CIRCUITTYPE — imported with circuit_category_id NULL, per the confirmed
-- decision.
ALTER TABLE circuits
    MODIFY COLUMN dp_id BIGINT NULL,
    ADD COLUMN circuit_category_id INT NULL AFTER dp_id,
    ADD CONSTRAINT fk_circuit_category FOREIGN KEY (circuit_category_id) REFERENCES circuit_categories (id);
