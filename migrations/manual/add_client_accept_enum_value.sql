-- Manual schema change (this project has no Flyway/Liquibase; spring.jpa.hibernate.ddl-auto=update
-- only adds new columns/tables, never new ENUM values — same manual-ALTER approach used for the
-- Stage 1 dispute/amendment states in add_dispute_amendment_enum_values.sql).
--
-- Adds the single terminal state needed for the client "accept bill" action, closing the Bill
-- Dispute & Amendment cycle (QA_Compliance_Consolidated_Report §2.1, FR-31/FR-32, SRS §5.2.3 &
-- §5.5.2.1):
--   payments.status          + CLIENT_ACCEPTED
--   payment_approvals.action + CLIENT_ACCEPTED   (audit row; the CLIENT is the actor)
--
-- Existing value lists were confirmed against the live `slt_fieldops_db` database directly
-- (SHOW COLUMNS ...), not inferred from the Java enums, since a MySQL ENUM ALTER MODIFY replaces
-- the entire value list and dropping an in-use value silently truncates any existing row using it.
-- payment_approvals.action retains SUBMITTED / REVIEWED / BILLED (live in the DB, not currently
-- produced by PaymentService) — preserved here regardless.
--
-- Verified via:
--   SHOW COLUMNS FROM payments LIKE 'status';
--   SHOW COLUMNS FROM payment_approvals LIKE 'action';
-- Result BEFORE (2026-07-21, slt_fieldops_db):
--   status  enum('DRAFT','FINAL','NOT_APPROVED','CLARIFICATION_REQUESTED','DISPUTED','PENDING_CLIENT_REVIEW')  YES  MUL  DRAFT
--   action  enum('SUBMITTED','REVIEWED','APPROVED','REJECTED','ADJUSTED','BILLED','CLARIFICATION_REQUESTED','AMENDED')  NO  <no default>

ALTER TABLE payments
    MODIFY COLUMN status ENUM(
        'DRAFT',
        'FINAL',
        'NOT_APPROVED',
        'CLARIFICATION_REQUESTED',
        'DISPUTED',
        'PENDING_CLIENT_REVIEW',
        'CLIENT_ACCEPTED'
    ) NULL DEFAULT 'DRAFT';

ALTER TABLE payment_approvals
    MODIFY COLUMN action ENUM(
        'SUBMITTED',
        'REVIEWED',
        'APPROVED',
        'REJECTED',
        'ADJUSTED',
        'BILLED',
        'CLARIFICATION_REQUESTED',
        'AMENDED',
        'CLIENT_ACCEPTED'
    ) NOT NULL;
