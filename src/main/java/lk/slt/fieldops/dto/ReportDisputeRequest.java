package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ReportDisputeRequest — Client reports an issue on an approved bill (FR-31, SRS 5.2.3).
 *
 * POST /api/billing/{id}/dispute
 * {
 *   "category":    "WRONG_AMOUNT",
 *   "description": "The chargeable total is higher than the quote I was given on-site.",
 *   "photoUrl":    "https://storage.slt.lk/disputes/d_bill42.jpg"   // optional
 * }
 *
 * Per SRS 5.2.3 the Client selects an issue category and enters a mandatory
 * description, with optional photo evidence. The category set is not enumerated
 * in the SRS (examples only, e.g. wrong amount / unrecognised material /
 * incorrect FOC-chargeable classification), so it is carried as validated text.
 */
public class ReportDisputeRequest {

    @NotBlank(message = "An issue category is required")
    @Size(max = 100, message = "Category must not exceed 100 characters")
    private String category;

    @NotBlank(message = "A description of the issue is required")
    private String description;

    private String photoUrl;

    public ReportDisputeRequest() {}

    public String getCategory()    { return category; }
    public String getDescription() { return description; }
    public String getPhotoUrl()    { return photoUrl; }

    public void setCategory(String v)    { this.category    = v; }
    public void setDescription(String v) { this.description = v; }
    public void setPhotoUrl(String v)    { this.photoUrl    = v; }
}
