package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "otp_records")
public class OtpRecord {

    public enum OtpPurpose { CLIENT_LOGIN, PASSWORD_RESET, PHONE_VERIFICATION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @Column(name = "otp_code", nullable = false, length = 6)
    private String otpCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private OtpPurpose purpose;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "is_used", nullable = false)
    private Boolean isUsed = false;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public OtpRecord() {}

    public static OtpRecordBuilder builder() { return new OtpRecordBuilder(); }

    public Long          getId()           { return id; }
    public String        getPhoneNumber()  { return phoneNumber; }
    public String        getOtpCode()      { return otpCode; }
    public OtpPurpose    getPurpose()      { return purpose; }
    public LocalDateTime getExpiresAt()    { return expiresAt; }
    public Boolean       getIsUsed()       { return isUsed; }
    public LocalDateTime getUsedAt()       { return usedAt; }
    public Integer       getAttemptCount() { return attemptCount; }
    public LocalDateTime getCreatedAt()    { return createdAt; }

    public void setId(Long v)                  { this.id           = v; }
    public void setPhoneNumber(String v)       { this.phoneNumber  = v; }
    public void setOtpCode(String v)           { this.otpCode      = v; }
    public void setPurpose(OtpPurpose v)       { this.purpose      = v; }
    public void setExpiresAt(LocalDateTime v)  { this.expiresAt    = v; }
    public void setIsUsed(Boolean v)           { this.isUsed       = v; }
    public void setUsedAt(LocalDateTime v)     { this.usedAt       = v; }
    public void setAttemptCount(Integer v)     { this.attemptCount = v; }

    public static class OtpRecordBuilder {
        private String        phoneNumber;
        private String        otpCode;
        private OtpPurpose    purpose;
        private LocalDateTime expiresAt;

        public OtpRecordBuilder phoneNumber(String v)       { this.phoneNumber = v; return this; }
        public OtpRecordBuilder otpCode(String v)           { this.otpCode     = v; return this; }
        public OtpRecordBuilder purpose(OtpPurpose v)       { this.purpose     = v; return this; }
        public OtpRecordBuilder expiresAt(LocalDateTime v)  { this.expiresAt   = v; return this; }

        public OtpRecord build() {
            OtpRecord o = new OtpRecord();
            o.setPhoneNumber(this.phoneNumber);
            o.setOtpCode(this.otpCode);
            o.setPurpose(this.purpose);
            o.setExpiresAt(this.expiresAt);
            return o;
        }
    }
}
