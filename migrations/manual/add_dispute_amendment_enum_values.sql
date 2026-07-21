-- Manual schema change (this project has no Flyway/Liquibase; spring.jpa.hibernate.ddl-auto=update
-- only adds new columns/tables, never new ENUM values — same manual-ALTER approach already needed
-- for the DaySession unique-constraint gap noted in QA_Compliance_Consolidated_Report §2.3).
--
-- Adds the two states needed for the Bill Dispute & Amendment feature
-- (QA_Compliance_Consolidated_Report §2.1, FR-31/FR-32, SRS §5.2.3 & §5.5.2.1):
--   payments.status          + DISPUTED, PENDING_CLIENT_REVIEW
--   payment_approvals.action + AMENDED
--
-- Existing value lists were confirmed against the live `slt_fieldops_db` database directly
-- (SHOW COLUMNS ...), not inferred from the Java enums, since a MySQL ENUM ALTER MODIFY replaces
-- the entire value list and dropping an in-use value silently truncates any existing row using it.
-- payment_approvals.action in particular has SUBMITTED / REVIEWED / BILLED live in the DB that
-- are not currently referenced by PaymentApproval.Action usages in PaymentService.java as read —
-- they are preserved here regardless.
--
-- Verified via:
--   SHOW COLUMNS FROM payments LIKE 'status';
--   SHOW COLUMNS FROM payment_approvals LIKE 'action';
-- Result (2026-07-21, slt_fieldops_db):
--   status  enum('DRAFT','FINAL','NOT_APPROVED','CLARIFICATION_REQUESTED')  YES  MUL  DRAFT  <default>
--   action  enum('SUBMITTED','REVIEWED','APPROVED','REJECTED','ADJUSTED','BILLED','CLARIFICATION_REQUESTED')  NO  <no default>

ALTER TABLE payments
    MODIFY COLUMN status ENUM(
        'DRAFT',
        'FINAL',
        'NOT_APPROVED',
        'CLARIFICATION_REQUESTED',
        'DISPUTED',
        'PENDING_CLIENT_REVIEW'
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
        'AMENDED'
    ) NOT NULL;
