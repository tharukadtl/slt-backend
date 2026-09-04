-- Stage D — Admin -> Work Group -> Team Lead -> self/Technician (SRS 5.5.1, 5.5.3, v1.9/v1.10)
--
-- Verified against the live schema before writing this (per the standing practice
-- from the OPMC rename near-miss): faults.work_group_id/work_group_name,
-- material_requests.work_group_id and work_group_allocations did not exist yet,
-- work_groups had no unique constraint on team_lead_id, and all three affected
-- tables were empty or near-empty (faults: 8 rows, material_requests: 0, work_groups: 0).
-- Every column added below is nullable, so there is no NOT NULL zero-fill risk this
-- time, but the check was still done rather than assumed.

ALTER TABLE faults
    ADD COLUMN work_group_id BIGINT NULL AFTER opmc_id,
    ADD COLUMN work_group_name VARCHAR(150) NULL AFTER work_group_id;

ALTER TABLE material_requests
    ADD COLUMN work_group_id BIGINT NULL AFTER opmc_id;

-- RES-020 — one Team Lead per Work Group. NULLs are excluded from a MySQL unique
-- index (any number of Work Groups may still have no Team Lead), so this is safe
-- to add even with existing NULL team_lead_id rows.
ALTER TABLE work_groups
    ADD UNIQUE KEY uk_work_group_team_lead (team_lead_id);

CREATE TABLE work_group_allocations (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    work_group_id       BIGINT NOT NULL,
    material_id         BIGINT NOT NULL,
    allocated_quantity  DECIMAL(12,3) NOT NULL DEFAULT 0,
    created_at          DATETIME NOT NULL,
    updated_at          DATETIME NULL,
    UNIQUE KEY uk_wg_allocation_material (work_group_id, material_id),
    CONSTRAINT fk_wga_workgroup FOREIGN KEY (work_group_id) REFERENCES work_groups(id),
    CONSTRAINT fk_wga_material  FOREIGN KEY (material_id)   REFERENCES materials(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
