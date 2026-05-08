package lk.slt.fieldops.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ClientRegisterRequest {

    @NotBlank(message = "First name is required")
    @JsonProperty("first_name")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @JsonProperty("last_name")
    private String lastName;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Invalid phone number format")
    private String phone;

    private String email;

    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    private String address;

    private String accountNumber;

    private String deviceInfo;

    public ClientRegisterRequest() {}

    public String getFirstName()     { return firstName; }
    public String getLastName()      { return lastName; }
    public String getPhone()         { return phone; }
    public String getEmail()         { return email; }
    public String getPassword()      { return password; }
    public String getAddress()       { return address; }
    public String getAccountNumber() { return accountNumber; }
    public String getDeviceInfo()    { return deviceInfo; }

    public void setFirstName(String v)     { this.firstName     = v; }
    public void setLastName(String v)      { this.lastName      = v; }
    public void setPhone(String v)         { this.phone         = v; }
    public void setEmail(String v)         { this.email         = v; }
    public void setPassword(String v)      { this.password      = v; }
    public void setAddress(String v)       { this.address       = v; }
    public void setAccountNumber(String v) { this.accountNumber = v; }
    public void setDeviceInfo(String v)    { this.deviceInfo    = v; }

    public String getFullName() {
        return (firstName + " " + lastName).trim();
    }
}
