package lk.slt.fieldops.service;

import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import lk.slt.fieldops.dto.BulkUserImportResponse;
import lk.slt.fieldops.dto.ChangePasswordRequest;
import lk.slt.fieldops.dto.CreateUserRequest;
import lk.slt.fieldops.dto.NotificationPreferencesDTO;
import lk.slt.fieldops.dto.UpdateProfileRequest;
import lk.slt.fieldops.dto.UpdateUserRequest;
import lk.slt.fieldops.dto.UserDTO;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.UserAuditLog;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.RefreshTokenRepository;
import lk.slt.fieldops.repository.UserAuditLogRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserService {

    // Roles that require SUPER_ADMIN to grant — a plain ADMIN cannot create or
    // promote a user into these, closing the escalation gap where any ADMIN
    // could otherwise hand out ADMIN/SUPER_ADMIN privilege unchecked.
    private static final Set<User.Role> PRIVILEGED_ROLES = Set.of(User.Role.ADMIN, User.Role.SUPER_ADMIN);

    private final UserRepository        userRepo;
    private final RefreshTokenRepository refreshTokenRepo;
    private final UserAuditLogRepository auditLogRepo;
    private final PasswordEncoder       passwordEncoder;
    private final OpmcRepository        opmcRepo;
    private final WorkGroupRepository   workGroupRepo;

    public UserService(UserRepository userRepo, RefreshTokenRepository refreshTokenRepo,
                        UserAuditLogRepository auditLogRepo, PasswordEncoder passwordEncoder,
                        OpmcRepository opmcRepo, WorkGroupRepository workGroupRepo) {
        this.userRepo         = userRepo;
        this.refreshTokenRepo = refreshTokenRepo;
        this.auditLogRepo     = auditLogRepo;
        this.passwordEncoder  = passwordEncoder;
        this.opmcRepo         = opmcRepo;
        this.workGroupRepo    = workGroupRepo;
    }

    /** Resolves a client-supplied workgroupId to a real WorkGroup, or null if none was supplied. */
    private WorkGroup resolveWorkGroup(Long workgroupId) {
        if (workgroupId == null) return null;
        return workGroupRepo.findById(workgroupId)
            .orElseThrow(() -> new RuntimeException("Work group " + workgroupId + " does not exist."));
    }

    /** Records an admin-initiated account change for REP-10 (Audit Trail Report). */
    private void logAudit(User target, UserAuditLog.Action action, Long performedBy,
                           String previousValue, String newValue) {
        UserAuditLog log = new UserAuditLog();
        log.setTargetUserId(target.getId());
        log.setTargetUserName(target.getFullName());
        log.setAction(action);
        if (performedBy != null) {
            userRepo.findById(performedBy).ifPresent(caller -> {
                log.setPerformedByName(caller.getFullName());
                log.setPerformedByRole(caller.getRole() != null ? caller.getRole().name() : null);
            });
        }
        log.setPerformedBy(performedBy);
        log.setPreviousValue(previousValue);
        log.setNewValue(newValue);
        log.setIpAddress(lk.slt.fieldops.shared.RequestContext.getClientIp());
        auditLogRepo.save(log);
    }

    @Transactional
    public UserDTO createUser(CreateUserRequest req, Long callerId) {
        if (userRepo.existsByUsername(req.getUsername())) {
            throw new RuntimeException("Username '" + req.getUsername() + "' is already taken.");
        }

        User.Role role;
        try {
            role = User.Role.valueOf(req.getRole().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role: " + req.getRole());
        }
        assertCanGrantRole(null, role, callerId);

        // No prior user row exists yet, so the only OPMC to check is the one
        // being requested — an Admin may only create users inside their own
        // OPMC. Super Admin is unscoped. Same pattern as
        // WorkGroupController.assertSameOpmcUnlessSuperAdmin.
        assertSameOpmcUnlessSuperAdmin(req.getOpmcId(), callerId);

        // Requiredness is intentionally unchanged — SUPER_ADMIN/CLIENT legitimately
        // have no OPMC, per real data. Only validate a *supplied* opmcId
        // actually exists, mirroring ResourcePlanConfirmationService.java:70.
        if (req.getOpmcId() != null && !opmcRepo.existsById(req.getOpmcId())) {
            throw new RuntimeException("OPMC " + req.getOpmcId() + " does not exist.");
        }

        // Requiredness IS the fix here, unlike opmcId above — TECHNICIAN/TEAM_LEAD
        // always belong to exactly one Work Group in the org hierarchy (SRS 5.5.0,
        // v1.9); there is no equivalent legitimate no-Work-Group case for these two
        // roles the way SUPER_ADMIN/CLIENT have no OPMC. Found unenforced during a
        // QA audit (RES-024): every material request submitted by such a user
        // silently bypassed the Work Group allocation layer in MaterialRequestService.
        if ((role == User.Role.TECHNICIAN || role == User.Role.TEAM_LEAD)
                && req.getWorkgroupId() == null) {
            throw new RuntimeException("Work group is required for " + role + " users.");
        }

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        String full = req.getFullName() != null ? req.getFullName().trim() : "";
        user.setFullName(full);
        int sp = full.indexOf(' ');
        if (sp > 0) {
            user.setFirstName(full.substring(0, sp));
            user.setLastName(full.substring(sp + 1).trim());
        } else {
            user.setFirstName(full.isEmpty() ? "User" : full);
            user.setLastName(full.isEmpty() ? "User" : full);
        }
        user.setEmail(blankToNull(req.getEmail()));
        user.setPhone(blankToNull(req.getPhone()));
        user.setAddress(blankToNull(req.getAddress()));
        user.setRole(role);
        user.setOpmcId(req.getOpmcId());
        user.setWorkgroup(resolveWorkGroup(req.getWorkgroupId()));
        user.setIsActive(true);

        User saved = userRepo.save(user);
        logAudit(saved, UserAuditLog.Action.CREATED, callerId, null, role.name());
        return mapToDTO(saved);
    }

    @Transactional
    public UserDTO updateUser(Long id, UpdateUserRequest req, Long callerId) {
        User user = findOrThrow(id);

        // An Admin may only act on users within their own OPMC; Super Admin is
        // unscoped. Checked against the target's OPMC as it stands before this
        // edit, mirroring WorkGroupController.assertSameOpmcUnlessSuperAdmin,
        // which checks the fetched entity before applying an update.
        assertSameOpmcUnlessSuperAdmin(user.getOpmcId(), callerId);

        user.setFullName(req.getFullName());
        user.setEmail(blankToNull(req.getEmail()));
        user.setPhone(blankToNull(req.getPhone()));
        if (req.getAddress() != null) {
            user.setAddress(blankToNull(req.getAddress()));
        }

        boolean roleChanged = false;
        String previousRole = user.getRole() != null ? user.getRole().name() : null;
        String newRoleName = null;
        if (req.getRole() != null) {
            User.Role newRole;
            try {
                newRole = User.Role.valueOf(req.getRole().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Invalid role: " + req.getRole());
            }
            if (newRole != user.getRole()) {
                assertCanGrantRole(user.getRole(), newRole, callerId);
                user.setRole(newRole);
                roleChanged = true;
                newRoleName = newRole.name();
            }
        }

        if (req.getOpmcId() != null) {
            user.setOpmcId(req.getOpmcId());
        }
        if (req.getWorkgroupId() != null) {
            user.setWorkgroup(resolveWorkGroup(req.getWorkgroupId()));
        }

        // Same requiredness as createUser, checked against the final post-edit
        // state — catches both a role change into TECHNICIAN/TEAM_LEAD with no
        // workgroupId supplied, and an edit to an already-TECHNICIAN/TEAM_LEAD
        // user who still has no Work Group (RES-024).
        if ((user.getRole() == User.Role.TECHNICIAN || user.getRole() == User.Role.TEAM_LEAD)
                && user.getWorkgroup() == null) {
            throw new RuntimeException("Work group is required for " + user.getRole() + " users.");
        }

        User saved = userRepo.save(user);
        if (roleChanged) {
            logAudit(saved, UserAuditLog.Action.ROLE_CHANGED, callerId, previousRole, newRoleName);
        } else {
            logAudit(saved, UserAuditLog.Action.DETAILS_UPDATED, callerId, null, null);
        }
        return mapToDTO(saved);
    }

    /**
     * Only a SUPER_ADMIN may move a role into or out of ADMIN/SUPER_ADMIN — a plain
     * ADMIN cannot self-escalate or escalate others into those roles (granting), and
     * symmetrically cannot demote a current ADMIN/SUPER_ADMIN out of them either.
     * {@code currentRole} is null on creation, where there is no prior role to demote from.
     */
    private void assertCanGrantRole(User.Role currentRole, User.Role targetRole, Long callerId) {
        boolean touchesPrivileged = PRIVILEGED_ROLES.contains(targetRole)
            || (currentRole != null && PRIVILEGED_ROLES.contains(currentRole));
        if (!touchesPrivileged) return;
        User caller = findOrThrow(callerId);
        if (caller.getRole() != User.Role.SUPER_ADMIN) {
            throw new RuntimeException(
                "Only a Super Admin can change a user's role into or out of ADMIN/SUPER_ADMIN.");
        }
    }

    /**
     * An Admin may only act on users within their own OPMC; Super Admin is
     * unscoped. Reuses WorkGroupController.assertSameOpmcUnlessSuperAdmin's
     * exact pattern, applied to user management instead of Work Groups.
     */
    private void assertSameOpmcUnlessSuperAdmin(Long targetOpmcId, Long callerId) {
        User caller = findOrThrow(callerId);
        if (caller.getRole() == User.Role.SUPER_ADMIN) return;

        Long callerOpmcId = caller.getOpmcId();
        if (callerOpmcId == null || targetOpmcId == null || !callerOpmcId.equals(targetOpmcId)) {
            throw new AccessDeniedException("This user does not belong to your OPMC.");
        }
    }

    @Transactional
    public void deactivateUser(Long id, Long callerId) {
        User user = findOrThrow(id);
        assertSameOpmcUnlessSuperAdmin(user.getOpmcId(), callerId);
        user.setIsActive(false);
        userRepo.save(user);
        logAudit(user, UserAuditLog.Action.DEACTIVATED, callerId, null, null);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User user = findOrThrow(userId);

        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Current password is incorrect.");
        }

        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepo.save(user);
    }

    @Transactional
    public String resetPassword(Long userId, Long callerId) {
        User user = findOrThrow(userId);
        String newPass = UUID.randomUUID().toString().substring(0, 12);
        user.setPasswordHash(passwordEncoder.encode(newPass));
        userRepo.save(user);
        logAudit(user, UserAuditLog.Action.PASSWORD_RESET, callerId, null, null);
        return newPass;
    }

    /**
     * Admin "reset access": revokes all of the user's active sessions and
     * forces them through OTP re-verification on their next login attempt,
     * rather than handing out a reusable password like resetPassword() does.
     */
    @Transactional
    public void resetAccess(Long userId, Long callerId) {
        User user = findOrThrow(userId);
        user.setForcePasswordChange(true);
        userRepo.save(user);
        refreshTokenRepo.revokeAllUserTokens(userId, LocalDateTime.now());
        logAudit(user, UserAuditLog.Action.ACCESS_RESET, callerId, null, null);
    }

    @Transactional(readOnly = true)
    public UserDTO getById(Long id) {
        return mapToDTO(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<UserDTO> getAll() {
        return userRepo.findAll().stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<UserDTO> getByRole(String roleStr) {
        try {
            User.Role role = User.Role.valueOf(roleStr.toUpperCase());
            return userRepo.findByRole(role).stream().map(this::mapToDTO).collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role: " + roleStr);
        }
    }

    @Transactional(readOnly = true)
    public List<UserDTO> getByOpmc(Long opmcId) {
        return userRepo.findByOpmcId(opmcId).stream().map(this::mapToDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<UserDTO> getActiveByRole(String roleStr) {
        try {
            User.Role role = User.Role.valueOf(roleStr.toUpperCase());
            return userRepo.findByRoleAndIsActiveTrue(role).stream()
                .map(this::mapToDTO).collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role: " + roleStr);
        }
    }

    @Transactional(readOnly = true)
    public List<UserDTO> getByRoleAndOpmc(String roleStr, Long opmcId) {
        try {
            User.Role role = User.Role.valueOf(roleStr.toUpperCase());
            return userRepo.findByOpmcIdAndRole(opmcId, role).stream()
                .map(this::mapToDTO).collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role: " + roleStr);
        }
    }

    @Transactional(readOnly = true)
    public List<UserDTO> getActiveByRoleAndOpmc(String roleStr, Long opmcId) {
        try {
            User.Role role = User.Role.valueOf(roleStr.toUpperCase());
            return userRepo.findActiveByRoleAndOpmc(role, opmcId).stream()
                .map(this::mapToDTO).collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role: " + roleStr);
        }
    }

    @Transactional
    public UserDTO updateProfile(Long userId, UpdateProfileRequest req) {
        User user = findOrThrow(userId);

        user.setFullName(req.getFullName().trim());
        user.setEmail(blankToNull(req.getEmail()));

        if (req.getLanguage() != null) {
            user.setPreferredLanguage(req.getLanguage());
        }

        NotificationPreferencesDTO prefs = req.getNotificationPreferences();
        if (prefs != null) {
            user.setNotifyStatusUpdates(prefs.isStatusUpdates());
            user.setNotifyTechnicianAssigned(prefs.isTechnicianAssigned());
            user.setNotifyJobCompleted(prefs.isJobCompleted());
            user.setNotifyBilling(prefs.isBilling());
            user.setNotifyPromotions(prefs.isPromotions());
        }

        return mapToDTO(userRepo.save(user));
    }

    @Transactional
    public void updateLastLogin(Long userId) {
        if (userId == null) return;
        userRepo.findById(userId).ifPresent(user -> {
            user.setLastLogin(LocalDateTime.now());
            userRepo.save(user);
        });
    }

    @Transactional
    public void updateFcmToken(Long userId, String fcmToken) {
        User user = findOrThrow(userId);
        user.setFcmToken(fcmToken);
        userRepo.save(user);
    }

    /**
     * Bulk-creates users from a CSV file. Expected header row (order fixed, no quoted commas):
     *   username,password,fullName,email,phone,address,role,opmcId,workgroupId
     * Only username, password, fullName, role are required; the rest may be blank.
     * Each row is created independently — one bad row doesn't stop the rest.
     */
    @Transactional
    public BulkUserImportResponse importUsersFromCsv(MultipartFile file, Long callerId) {
        List<String> createdUsernames = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int totalRows = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header row, skipped
            int rowNum = 1;
            while ((line = reader.readLine()) != null) {
                rowNum++;
                if (line.isBlank()) continue;
                totalRows++;
                String[] f = line.split(",", -1);
                try {
                    CreateUserRequest req = new CreateUserRequest();
                    req.setUsername(field(f, 0));
                    req.setPassword(field(f, 1));
                    req.setFullName(field(f, 2));
                    req.setEmail(field(f, 3));
                    req.setPhone(field(f, 4));
                    req.setAddress(field(f, 5));
                    req.setRole(field(f, 6));
                    String opmcIdStr = field(f, 7);
                    if (opmcIdStr != null && !opmcIdStr.isBlank()) {
                        req.setOpmcId(Long.parseLong(opmcIdStr.trim()));
                    }
                    String workgroupIdStr = field(f, 8);
                    if (workgroupIdStr != null && !workgroupIdStr.isBlank()) {
                        req.setWorkgroupId(Long.parseLong(workgroupIdStr.trim()));
                    }

                    if (req.getUsername() == null || req.getUsername().isBlank()) {
                        throw new RuntimeException("username is required");
                    }
                    if (req.getPassword() == null || req.getPassword().isBlank()) {
                        throw new RuntimeException("password is required");
                    }
                    if (req.getFullName() == null || req.getFullName().isBlank()) {
                        throw new RuntimeException("fullName is required");
                    }

                    createUser(req, callerId);
                    createdUsernames.add(req.getUsername());
                } catch (Exception e) {
                    errors.add("Row " + rowNum + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read CSV file: " + e.getMessage());
        }

        BulkUserImportResponse resp = new BulkUserImportResponse();
        resp.setTotalRows(totalRows);
        resp.setSuccessCount(createdUsernames.size());
        resp.setFailureCount(errors.size());
        resp.setCreatedUsernames(createdUsernames);
        resp.setErrors(errors);
        return resp;
    }

    private static String field(String[] fields, int idx) {
        return idx < fields.length ? fields[idx].trim() : null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private User findOrThrow(Long id) {
        return userRepo.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    public UserDTO mapToDTO(User u) {
        UserDTO dto = new UserDTO();
        dto.setId(u.getId());
        dto.setUsername(u.getUsername());
        dto.setFullName(u.getFullName());
        dto.setEmail(u.getEmail());
        dto.setPhone(u.getPhone());
        dto.setRole(u.getRole() != null ? u.getRole().name() : null);
        dto.setOpmcId(u.getOpmcId());
        dto.setOpmcName(u.getOpmcName());
        dto.setWorkgroupId(u.getWorkgroup() != null ? u.getWorkgroup().getId() : null);
        dto.setWorkgroupName(u.getWorkgroup() != null ? u.getWorkgroup().getName() : null);
        dto.setIsActive(u.getIsActive());
        dto.setLastLogin(u.getLastLogin());
        dto.setCreatedAt(u.getCreatedAt());
        dto.setLanguage(u.getPreferredLanguage() != null ? u.getPreferredLanguage().name() : null);

        NotificationPreferencesDTO prefs = new NotificationPreferencesDTO();
        prefs.setStatusUpdates(Boolean.TRUE.equals(u.getNotifyStatusUpdates()));
        prefs.setTechnicianAssigned(Boolean.TRUE.equals(u.getNotifyTechnicianAssigned()));
        prefs.setJobCompleted(Boolean.TRUE.equals(u.getNotifyJobCompleted()));
        prefs.setBilling(Boolean.TRUE.equals(u.getNotifyBilling()));
        prefs.setPromotions(Boolean.TRUE.equals(u.getNotifyPromotions()));
        dto.setNotificationPreferences(prefs);

        return dto;
    }
}
