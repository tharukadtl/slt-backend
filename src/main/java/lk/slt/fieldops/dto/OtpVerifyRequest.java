package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class OtpVerifyRequest {

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^07[0-9]{8}$", message = "Invalid phone number format")
    private String phoneNumber;

    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "^[0-9]{6}$", message = "Please enter complete OTP")
    private String otp;

    private String deviceInfo;

    public OtpVerifyRequest() {}

    public String getPhoneNumber() { return phoneNumber; }
    public String getOtp()         { return otp; }
    public String getDeviceInfo()  { return deviceInfo; }

    public void setPhoneNumber(String v) { this.phoneNumber = v; }
    public void setOtp(String v)         { this.otp         = v; }
    public void setDeviceInfo(String v)  { this.deviceInfo  = v; }
}
