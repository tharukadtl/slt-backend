package lk.slt.fieldops.dto;

import java.util.List;

/** ConfirmResourcePlanResponse — result of POST /api/resource-plans/confirm. */
public class ConfirmResourcePlanResponse {

    private int confirmedCount;
    private int skippedCount;
    private List<String> skippedReasons;

    public ConfirmResourcePlanResponse() {}

    public ConfirmResourcePlanResponse(int confirmedCount, int skippedCount, List<String> skippedReasons) {
        this.confirmedCount = confirmedCount;
        this.skippedCount = skippedCount;
        this.skippedReasons = skippedReasons;
    }

    public int getConfirmedCount()          { return confirmedCount; }
    public int getSkippedCount()            { return skippedCount; }
    public List<String> getSkippedReasons() { return skippedReasons; }

    public void setConfirmedCount(int v)            { this.confirmedCount = v; }
    public void setSkippedCount(int v)              { this.skippedCount   = v; }
    public void setSkippedReasons(List<String> v)   { this.skippedReasons = v; }
}
