package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.BulkUserImportResponse;
import lk.slt.fieldops.dto.ChangePasswordRequest;
import lk.slt.fieldops.dto.CreateUserRequest;
import lk.slt.fieldops.dto.UpdateProfileRequest;
import lk.slt.fieldops.dto.UpdateUserRequest;
import lk.slt.fieldops.dto.UserDTO;
import lk.slt.fieldops.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<UserDTO> create(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal Long callerId) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(userService.createUser(request, callerId));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<UserDTO>> getAll(
            @RequestParam(required = false) String  role,
            @RequestParam(required = false) Long    opmcId,
            @RequestParam(required = false) Boolean activeOnly) {
        if (role != null && opmcId != null) {
            return ResponseEntity.ok(
                Boolean.TRUE.equals(activeOnly)
                    ? userService.getActiveByRoleAndOpmc(role, opmcId)
                    : userService.getByRoleAndOpmc(role, opmcId));
        }
        if (role != null)   return ResponseEntity.ok(userService.getByRole(role));
        if (opmcId != null) return ResponseEntity.ok(userService.getByOpmc(opmcId));
        return ResponseEntity.ok(userService.getAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<UserDTO> getById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<UserDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal Long callerId) {
        return ResponseEntity.ok(userService.updateUser(id, request, callerId));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Map<String, String>> deactivate(
            @PathVariable Long id,
            @AuthenticationPrincipal Long callerId) {
        userService.deactivateUser(id, callerId);
        return ResponseEntity.ok(Map.of("message", "User deactivated successfully"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal Long userId) {
        userService.changePassword(userId, request);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    // QA_Compliance_Consolidated_Report.md Stage G Minor — this response used to echo the
    // freshly-generated password back in plaintext. Investigated before fixing: grepped
    // frontend-admin end to end and confirmed resetPassword() (api/users.js:49) has no caller
    // anywhere in the UI — nothing displays this value to the Admin to relay to the user, so
    // there's no existing delivery flow this fix needs to replace. UserService.resetPassword
    // still generates and persists a real new password server-side (unchanged) — only the
    // plaintext value is no longer returned in the response. Note this endpoint currently has
    // no way to communicate the new password to anyone once generated, which is exactly why
    // resetAccess() below (SRS 5.5.4, "force re-OTP on next login") is this system's real,
    // already-wired admin-reset capability — it never generates a password to leak in the
    // first place. This endpoint is left in place, not removed, since some caller may still
    // reasonably want a password-reset action distinct from access-reset; only the leak is fixed.
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Map<String, String>> resetPassword(
            @PathVariable Long id,
            @AuthenticationPrincipal Long callerId) {
        userService.resetPassword(id, callerId);
        return ResponseEntity.ok(Map.of(
            "message", "Password reset successfully"
        ));
    }

    // Revokes all of this user's sessions and forces OTP re-verification on their next login.
    @PostMapping("/{id}/reset-access")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Map<String, String>> resetAccess(
            @PathVariable Long id,
            @AuthenticationPrincipal Long callerId) {
        userService.resetAccess(id, callerId);
        return ResponseEntity.ok(Map.of("message", "Access reset — user must re-verify via OTP on next login"));
    }

    @PatchMapping("/profile")
    public ResponseEntity<UserDTO> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(userService.updateProfile(userId, request));
    }

    @PostMapping("/fcm-token")
    public ResponseEntity<Map<String, String>> updateFcmToken(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Long userId) {
        String fcmToken = body.get("fcmToken");
        if (fcmToken == null || fcmToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fcmToken is required"));
        }
        userService.updateFcmToken(userId, fcmToken);
        return ResponseEntity.ok(Map.of("message", "FCM token updated"));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<UserDTO>> getActiveByRole(@RequestParam String role) {
        return ResponseEntity.ok(userService.getActiveByRole(role));
    }

    // CSV columns: username,password,fullName,email,phone,address,role,opmcId,workgroupId
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<BulkUserImportResponse> importUsers(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal Long callerId) {
        return ResponseEntity.ok(userService.importUsersFromCsv(file, callerId));
    }
}
