package lk.slt.fieldops.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(unique = true, nullable = false, length = 255)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public RefreshToken() {}

    public static RefreshTokenBuilder builder() { return new RefreshTokenBuilder(); }

    public boolean isValid() {
        return revokedAt == null && LocalDateTime.now().isBefore(expiresAt);
    }

    public Long          getId()         { return id; }
    public Long          getUserId()     { return userId; }
    public String        getToken()      { return token; }
    public LocalDateTime getExpiresAt()  { return expiresAt; }
    public LocalDateTime getRevokedAt()  { return revokedAt; }
    public String        getDeviceInfo() { return deviceInfo; }
    public LocalDateTime getCreatedAt()  { return createdAt; }

    public void setId(Long v)                 { this.id         = v; }
    public void setUserId(Long v)             { this.userId     = v; }
    public void setToken(String v)            { this.token      = v; }
    public void setExpiresAt(LocalDateTime v) { this.expiresAt  = v; }
    public void setRevokedAt(LocalDateTime v) { this.revokedAt  = v; }
    public void setDeviceInfo(String v)       { this.deviceInfo = v; }

    public static class RefreshTokenBuilder {
        private Long          userId;
        private String        token;
        private LocalDateTime expiresAt;
        private String        deviceInfo;

        public RefreshTokenBuilder userId(Long v)             { this.userId     = v; return this; }
        public RefreshTokenBuilder token(String v)            { this.token      = v; return this; }
        public RefreshTokenBuilder expiresAt(LocalDateTime v) { this.expiresAt  = v; return this; }
        public RefreshTokenBuilder deviceInfo(String v)       { this.deviceInfo = v; return this; }

        public RefreshToken build() {
            RefreshToken rt = new RefreshToken();
            rt.setUserId(this.userId);
            rt.setToken(this.token);
            rt.setExpiresAt(this.expiresAt);
            rt.setDeviceInfo(this.deviceInfo);
            return rt;
        }
    }
}
