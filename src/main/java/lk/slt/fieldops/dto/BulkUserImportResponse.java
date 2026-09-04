package lk.slt.fieldops.dto;

import java.util.List;

/** Per-row success/failure report for POST /api/users/import (CSV bulk import). */
public class BulkUserImportResponse {

    private int totalRows;
    private int successCount;
    private int failureCount;
    private List<String> createdUsernames;
    private List<String> errors;

    public BulkUserImportResponse() {}

    public int getTotalRows()               { return totalRows; }
    public int getSuccessCount()            { return successCount; }
    public int getFailureCount()            { return failureCount; }
    public List<String> getCreatedUsernames(){ return createdUsernames; }
    public List<String> getErrors()         { return errors; }

    public void setTotalRows(int v)                     { this.totalRows        = v; }
    public void setSuccessCount(int v)                  { this.successCount     = v; }
    public void setFailureCount(int v)                  { this.failureCount     = v; }
    public void setCreatedUsernames(List<String> v)     { this.createdUsernames = v; }
    public void setErrors(List<String> v)               { this.errors           = v; }
}
