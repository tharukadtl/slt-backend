package lk.slt.fieldops.job.dto;

import jakarta.validation.constraints.NotNull;

/**
 * CreateJobRequest — Team Lead assigns a fault to a Technician as a Job.
 *
 * POST /api/jobs
 * {
 *   "faultId":      12,
 *   "technicianId": 8,
 *   "priority":     "HIGH"
 * }
 */
public class CreateJobRequest {

    @NotNull(message = "Fault ID is required")
    private Long faultId;

    @NotNull(message = "Technician ID is required")
    private Long technicianId;

    private String priority;   // HIGH / MEDIUM / LOW — defaults to fault's priority

    public CreateJobRequest() {}

    public Long   getFaultId()     { return faultId; }
    public Long   getTechnicianId(){ return technicianId; }
    public String getPriority()    { return priority; }

    public void setFaultId(Long v)      { this.faultId      = v; }
    public void setTechnicianId(Long v) { this.technicianId = v; }
    public void setPriority(String v)   { this.priority     = v; }
}
