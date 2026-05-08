package lk.slt.fieldops.controller;

import jakarta.validation.Valid;
import lk.slt.fieldops.dto.ChangePasswordRequest;
import lk.slt.fieldops.dto.CreateUserRequest;
import lk.slt.fieldops.dto.UpdateUserRequest;
import lk.slt.fieldops.dto.UserDTO;
import lk.slt.fieldops.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
    public ResponseEntity<UserDTO> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(userService.createUser(request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','TEAM_LEAD')")
    public ResponseEntity<List<UserDTO>> getAll(
            @RequestParam(required = false) String  role,
            @RequestParam(required = false) Long    branchId,
            @RequestParam(required = false) Boolean activeOnly) {
        if (role != null && branchId != null) {
            return ResponseEntity.ok(
                Boolean.TRUE.equals(activeOnly)
                    ? userService.getActiveByRoleAndBranch(role, branchId)
                    : userService.getByRoleAndBranch(role, branchId));
        }
        if (role != null)     return ResponseEntity.ok(userService.getByRole(role));
        if (branchId != null) return ResponseEntity.ok(userService.getByBranch(branchId));
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
            @Valid @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Map<String, String>> deactivate(@PathVariable Long id) {
        userService.deactivateUser(id);
        return ResponseEntity.ok(Map.of("message", "User deactivated successfully"));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal Long userId) {
        userService.changePassword(userId, request);
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN')")
    public ResponseEntity<Map<String, String>> resetPassword(@PathVariable Long id) {
        String newPassword = userService.resetPassword(id);
        return ResponseEntity.ok(Map.of(
            "message", "Password reset successfully",
            "newPassword", newPassword
        ));
    }

    @PatchMapping("/profile")
    public ResponseEntity<UserDTO> updateProfile(
            @RequestBody java.util.Map<String, Object> body,
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(userService.updateProfile(userId, body));
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
}
