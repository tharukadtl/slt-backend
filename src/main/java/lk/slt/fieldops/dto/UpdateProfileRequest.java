package lk.slt.fieldops.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lk.slt.fieldops.entity.User;

/**
 * UpdateProfileRequest — any authenticated user updates their own profile.
 *
 * PATCH /api/users/profile
 * {
 *   "fullName":                "Jane Doe",
 *   "email":                   "jane@example.com",
 *   "language":                "ENGLISH",
 *   "notificationPreferences": {
 *     "statusUpdates":       true,
 *     "technicianAssigned":  true,
 *     "jobCompleted":        true,
 *     "billing":             true,
 *     "promotions":          false
 *   }
 * }
 *
 * Field names are the single source of truth on both sides — SLTMobileApp's
 * authService.updateProfile() sends exactly these names, no remapping layer.
 * This DTO replaces what used to be a raw Map<String,Object> body (the same
 * "raw Map, no @Valid" pattern already fixed for the 5 backend security
 * findings this session — AuthController's DTOs are the reference pattern).
 */
public class UpdateProfileRequest {

    @NotBlank(message = "Full name is required")
    private String fullName;

    // Optional — a blank value clears it (matches the pre-existing service
    // behavior); User.email has no @NotBlank/nullable=false constraint.
    private String email;

    // Optional — omitted keeps the user's current language. Typed directly
    // against the entity's own enum so an invalid value is a clean 400 at
    // deserialization, not silently ignored.
    private User.Language language;

    @Valid
    private NotificationPreferencesDTO notificationPreferences;

    public UpdateProfileRequest() {}

    public String       getFullName()               { return fullName; }
    public String       getEmail()                   { return email; }
    public User.Language getLanguage()               { return language; }
    public NotificationPreferencesDTO getNotificationPreferences() { return notificationPreferences; }

    public void setFullName(String v)                { this.fullName = v; }
    public void setEmail(String v)                   { this.email = v; }
    public void setLanguage(User.Language v)         { this.language = v; }
    public void setNotificationPreferences(NotificationPreferencesDTO v) { this.notificationPreferences = v; }
}
