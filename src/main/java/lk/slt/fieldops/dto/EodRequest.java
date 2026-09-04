package lk.slt.fieldops.dto;

/**
 * EodRequest — Team Lead submits End of Day.
 *
 * POST /api/jobs/eod
 * {
 *   "odometerEnd":          45480,
 *   "notes":                "All jobs completed. 1 job returned due to missing parts.",
 *   "routingOption":        "CARRY_OVER",   // FORWARD_TO_ADMIN | CARRY_OVER | REASSIGN_TEAM_LEAD — optional,
 *                                            // defaults to CARRY_OVER (today's only behavior) when omitted
 *   "reassignToTeamLeadId": null            // required only when routingOption is REASSIGN_TEAM_LEAD
 * }
 *
 * FR-20 / SRS 5.4.2.1 — EOD Routing Options for whatever jobs are still open
 * when the Team Lead closes the day: forward to Admin for reassignment, carry
 * over to the Team Lead's own next day, or reassign to another Team Lead.
 */
public class EodRequest {

    public enum RoutingOption { FORWARD_TO_ADMIN, CARRY_OVER, REASSIGN_TEAM_LEAD }

    private Integer odometerEnd;

    private String notes;

    private RoutingOption routingOption;

    private Long reassignToTeamLeadId;

    public EodRequest() {}

    public Integer      getOdometerEnd()          { return odometerEnd; }
    public String       getNotes()                { return notes; }
    public RoutingOption getRoutingOption()        { return routingOption; }
    public Long          getReassignToTeamLeadId() { return reassignToTeamLeadId; }

    public void setOdometerEnd(Integer v)           { this.odometerEnd          = v; }
    public void setNotes(String v)                  { this.notes                = v; }
    public void setRoutingOption(RoutingOption v)   { this.routingOption        = v; }
    public void setReassignToTeamLeadId(Long v)     { this.reassignToTeamLeadId = v; }
}
