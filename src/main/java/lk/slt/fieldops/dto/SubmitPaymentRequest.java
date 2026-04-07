package lk.slt.fieldops.payment.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * SubmitPaymentRequest — Team Lead submits payment after job completion.
 *
 * POST /api/payments
 * {
 *   "jobId":                    12,
 *   "materialsFocTotal":        0.00,
 *   "materialsChargeableTotal": 4500.00,
 *   "labourCharge":             0.00,
 *   "customerSignatureUrl":     "https://storage.slt.lk/signatures/sig_job12.png",
 *   "jobPhotosUrls":            "https://storage.slt.lk/photos/p1.jpg,https://storage.slt.lk/photos/p2.jpg",
 *   "workSummary":              "Replaced ADSL router. Customer confirmed working."
 * }
 */
public class SubmitPaymentRequest {

    @NotNull(message = "Job ID is required")
    private Long jobId;

    private BigDecimal materialsFocTotal        = BigDecimal.ZERO;
    private BigDecimal materialsChargeableTotal = BigDecimal.ZERO;
    private BigDecimal labourCharge             = BigDecimal.ZERO;
    private String     customerSignatureUrl;
    private String     jobPhotosUrls;
    private String     materialJustification;
    private String     workSummary;

    public SubmitPaymentRequest() {}

    public Long       getJobId()                     { return jobId; }
    public BigDecimal getMaterialsFocTotal()         { return materialsFocTotal; }
    public BigDecimal getMaterialsChargeableTotal()  { return materialsChargeableTotal; }
    public BigDecimal getLabourCharge()              { return labourCharge; }
    public String     getCustomerSignatureUrl()      { return customerSignatureUrl; }
    public String     getJobPhotosUrls()             { return jobPhotosUrls; }
    public String     getMaterialJustification()     { return materialJustification; }
    public String     getWorkSummary()               { return workSummary; }

    public void setJobId(Long v)                           { this.jobId                   = v; }
    public void setMaterialsFocTotal(BigDecimal v)         { this.materialsFocTotal        = v; }
    public void setMaterialsChargeableTotal(BigDecimal v)  { this.materialsChargeableTotal = v; }
    public void setLabourCharge(BigDecimal v)              { this.labourCharge             = v; }
    public void setCustomerSignatureUrl(String v)          { this.customerSignatureUrl     = v; }
    public void setJobPhotosUrls(String v)                 { this.jobPhotosUrls            = v; }
    public void setMaterialJustification(String v)         { this.materialJustification    = v; }
    public void setWorkSummary(String v)                   { this.workSummary              = v; }
}
