-- Drop the dangling legacy `workgroups` (no-underscore) table and its FKs.
--
-- Found while fixing RES-024 (workgroupId requiredness for TECHNICIAN/TEAM_LEAD,
-- QA_Compliance_Consolidated_Report.md): users.workgroup_id carries TWO
-- simultaneous foreign-key constraints at once -- the real, Hibernate-managed
-- one to work_groups(id) (the WorkGroup entity table, Stage C) and a stale
-- fk_user_workgroup pointing at a dead legacy `workgroups` table (no underscore,
-- predates the OPMC rename -- still carries a NOT NULL branch_id column, and no
-- Java entity anywhere in fieldops/src/main maps to it any more, confirmed by
-- grep). Both FKs must be satisfied simultaneously on every INSERT/UPDATE, which
-- is only possible if a row with the same id exists in both tables at once --
-- so users.workgroup_id could never actually be set to a non-null value in this
-- environment, for any role, until this migration. Same collision independently
-- confirmed on jobs.workgroup_id (fk_job_workgroup) and kpi_targets.workgroup_id
-- (fk_kt_workgroup) -- neither of those two has a competing Hibernate FK to
-- work_groups at all, so dropping the legacy FK there simply leaves an
-- unconstrained Long column, matching the established pattern already used
-- elsewhere (Fault.workGroupId, MaterialRequest.workGroupId are both plain,
-- unconstrained Long columns with no DB-level FK).
--
-- Verified before writing, per the OPMC-rename near-miss's standing practice
-- (never trust a migration's apparent safety without checking real data first):
-- `workgroups` has 0 rows (confirmed via a live count), and a full-tree grep for
-- `"workgroups"` (the literal table name, no underscore) across
-- fieldops/src/main/java returns zero hits -- nothing maps to it. Safe to drop
-- the three constraints and the table outright, not just deprecate it.

ALTER TABLE users       DROP FOREIGN KEY fk_user_workgroup;
ALTER TABLE jobs         DROP FOREIGN KEY fk_job_workgroup;
ALTER TABLE kpi_targets  DROP FOREIGN KEY fk_kt_workgroup;

DROP TABLE workgroups;
