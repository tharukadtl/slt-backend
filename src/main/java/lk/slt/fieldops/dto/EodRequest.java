package lk.slt.fieldops.dto;

/**
 * EodRequest — Team Lead submits End of Day.
 *
 * POST /api/jobs/eod
 * {
 *   "odometerEnd": 45480,
 *   "notes":       "All jobs completed. 1 job returned due to missing parts."
 * }
 */
public class EodRequest {

    private Integer odometerEnd;

    private String notes;

    public EodRequest() {}

    public Integer getOdometerEnd() { return odometerEnd; }
    public String  getNotes()       { return notes; }

    public void setOdometerEnd(Integer v) { this.odometerEnd = v; }
    public void setNotes(String v)        { this.notes       = v; }
}
