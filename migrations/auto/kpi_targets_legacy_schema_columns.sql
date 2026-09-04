-- AUTO-APPLIED schema fix (this project has no Flyway/Liquibase; spring.jpa.hibernate.ddl-auto=update
-- only adds new columns/tables from the current entity mapping, it never drops or reconciles ones an
-- entity used to have and no longer does). See migrations/README.md for what "auto-applied" means here
-- and why this file lives in migrations/auto/ rather than migrations/manual/ alongside its 12 siblings.
--
-- kpi_targets carries 13 columns the current KpiTarget entity (src/main/java/lk/slt/fieldops/entity/
-- KpiTarget.java) does not map at all: target_type, workgroup_id, technician_id, period_type,
-- period_year, period_month, period_week, target_jobs_completed, target_avg_resolution_hours,
-- target_first_time_fix_rate, target_customer_satisfaction, target_sla_compliance_rate, set_by_id.
-- They are the remains of an earlier KpiTarget design (per-workgroup/technician targets on a
-- DAILY/WEEKLY/MONTHLY period_type, evidently superseded by the current title/period/category/
-- kpi_branch_id shape) — ddl-auto=update carried them forward forever since it never drops a column.
-- Three are NOT NULL with no default (target_type, period_type has a default so is fine, period_year
-- does not), so on a database built from ONLY the current entity mapping (a fresh Testcontainers
-- instance, or any DR rebuild from schema + migrations) no row can be written into this legacy shape
-- at all — surfaced as Hibernate SQLGrammarException "Unknown column 'target_type' in 'field list'"
-- (KpiCalculationServiceQueryEfficiencyTest, KpiTargetServiceTest — both insert a fixture row through
-- this legacy shape natively, since KpiCalculationService.assignTarget's own writes are a separate,
-- already-documented defect: QA_Compliance_Consolidated_Report.md, KpiTargetServiceTest's own javadoc).
--
-- This is a genuine disaster-recovery gap independent of any test: 450 live rows (slt_fieldops_db,
-- AUTO_INCREMENT=451 at time of writing) already carry real target_type/period_year data that a
-- schema rebuilt from the current entity mapping alone would have nowhere to put.
--
-- Verified via SHOW CREATE TABLE kpi_targets against the live slt_fieldops_db database directly
-- (2026-09-03), not inferred from any entity or DTO — column types, nullability and the one real
-- foreign key (technician_id -> users, ON DELETE CASCADE) below are copied from that output verbatim.
--
-- Idempotency, by design not by MySQL syntax: MySQL 8.0 has no ADD COLUMN/ADD KEY IF NOT EXISTS
-- (confirmed empirically — ERROR 1064 on all three forms tried). This file is applied via
-- spring.sql.init.schema-locations with continue-on-error: true, scoped to migrations/auto/*.sql only
-- (src/test/resources/application.yml) — safe here specifically because the only possible error on a
-- repeat application of an ALTER ADD COLUMN/ADD KEY/ADD CONSTRAINT statement is "already exists", which
-- is the correct outcome to swallow. A test suite creates many distinct Spring context caches
-- (different @MockBean sets, different security config), each triggering its own datasource
-- initialization against the SAME shared Testcontainers database — the first context to boot applies
-- this file for real, every subsequent one hits "duplicate column"/"duplicate key" and is silently,
-- correctly skipped. Any future file added to migrations/auto/ must hold the same property (every
-- statement's only possible re-application error is a duplicate-exists error) — see migrations/README.md.

ALTER TABLE kpi_targets
    ADD COLUMN target_type ENUM('INDIVIDUAL', 'TEAM', 'BRANCH') NOT NULL,
    ADD COLUMN workgroup_id BIGINT NULL,
    ADD COLUMN technician_id BIGINT NULL,
    ADD COLUMN period_type ENUM('DAILY', 'WEEKLY', 'MONTHLY') NOT NULL DEFAULT 'MONTHLY',
    ADD COLUMN period_year SMALLINT NOT NULL,
    ADD COLUMN period_month TINYINT NULL,
    ADD COLUMN period_week TINYINT NULL,
    ADD COLUMN target_jobs_completed INT NOT NULL DEFAULT 0,
    ADD COLUMN target_avg_resolution_hours DECIMAL(5, 2) NULL,
    ADD COLUMN target_first_time_fix_rate DECIMAL(5, 2) NULL,
    ADD COLUMN target_customer_satisfaction DECIMAL(3, 1) NULL,
    ADD COLUMN target_sla_compliance_rate DECIMAL(5, 2) NULL,
    ADD COLUMN set_by_id BIGINT NULL;

ALTER TABLE kpi_targets
    ADD KEY fk_kt_workgroup (workgroup_id),
    ADD KEY idx_kt_technician (technician_id),
    ADD KEY idx_kt_period (period_year, period_month),
    ADD CONSTRAINT fk_kt_technician FOREIGN KEY (technician_id) REFERENCES users (id)
        ON DELETE CASCADE ON UPDATE RESTRICT;
