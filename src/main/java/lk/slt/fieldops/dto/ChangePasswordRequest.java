package lk.slt.fieldops.user.dto;

import jakarta.validation.constraints.NotBlank;

public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @NotBlank(message = "New password is required")
    private String newPassword;

    public ChangePasswordRequest() {}

    public String getCurrentPassword() { return currentPassword; }
    public String getNewPassword()     { return newPassword; }

    public void setCurrentPassword(String v) { this.currentPassword = v; }
    public void setNewPassword(String v)     { this.newPassword     = v; }
}
