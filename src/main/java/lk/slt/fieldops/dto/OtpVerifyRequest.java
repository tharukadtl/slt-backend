package lk.slt.fieldops.dto;

import jakarta.validation.constraints.NotBlank;

public class OtpVerifyRequest {

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @NotBlank(message = "OTP is required")
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
