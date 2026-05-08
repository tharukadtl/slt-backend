package lk.slt.fieldops.service;

import lk.slt.fieldops.shared.exception.ResourceNotFoundException;
import lk.slt.fieldops.dto.ChangePasswordRequest;
import lk.slt.fieldops.dto.CreateUserRequest;
import lk.slt.fieldops.dto.UpdateUserRequest;
import lk.slt.fieldops.dto.UserDTO;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository  userRepo;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo        = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserDTO createUser(CreateUserRequest req) {
        if (userRepo.existsByUsername(req.getUsername())) {
            throw new RuntimeException("Username '" + req.getUsername() + "' is already taken.");
        }

        User.Role role;
        try {
            role = User.Role.valueOf(req.getRole().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role: " + req.getRole());
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
        user.setRole(role);
        user.setBranchId(req.getBranchId());
        user.setIsActive(true);

        return mapToDTO(userRepo.save(user));
    }

    @Transactional
    public UserDTO updateUser(Long id, UpdateUserRequest req) {
        User user = findOrThrow(id);

        user.setFullName(req.getFullName());
        user.setEmail(blankToNull(req.getEmail()));
        user.setPhone(blankToNull(req.getPhone()));

        if (req.getRole() != null) {
            try {
                user.setRole(User.Role.valueOf(req.getRole().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("Invalid role: " + req.getRole());
            }
        }

        if (req.getBranchId() != null) {
            user.setBranchId(req.getBranchId());
        }

        return mapToDTO(userRepo.save(user));
    }

    @Transactional
    public void deactivateUser(Long id) {
        User user = findOrThrow(id);
        user.setIsActive(false);
        userRepo.save(user);
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
    public String resetPassword(Long userId) {
        User user = findOrThrow(userId);
        String newPass = "Welcome@" + user.getUsername() + "123";
        user.setPasswordHash(passwordEncoder.encode(newPass));
        userRepo.save(user);
        return newPass;
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
    public List<UserDTO> getByBranch(Long branchId) {
        return userRepo.findByBranchId(branchId).stream().map(this::mapToDTO).collect(Collectors.toList());
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
    public List<UserDTO> getByRoleAndBranch(String roleStr, Long branchId) {
        try {
            User.Role role = User.Role.valueOf(roleStr.toUpperCase());
            return userRepo.findByBranchIdAndRole(branchId, role).stream()
                .map(this::mapToDTO).collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role: " + roleStr);
        }
    }

    @Transactional(readOnly = true)
    public List<UserDTO> getActiveByRoleAndBranch(String roleStr, Long branchId) {
        try {
            User.Role role = User.Role.valueOf(roleStr.toUpperCase());
            return userRepo.findActiveByRoleAndBranch(role, branchId).stream()
                .map(this::mapToDTO).collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid role: " + roleStr);
        }
    }

    @Transactional
    public UserDTO updateProfile(Long userId, java.util.Map<String, Object> req) {
        User user = findOrThrow(userId);

        Object fullName = req.get("fullName");
        if (fullName instanceof String s && !s.isBlank()) {
            user.setFullName(s.trim());
        }

        Object email = req.get("email");
        if (email instanceof String s) {
            user.setEmail(s.isBlank() ? null : s.trim());
        }

        Object language = req.get("language");
        if (language instanceof String s && !s.isBlank()) {
            try {
                user.setPreferredLanguage(User.Language.valueOf(s.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
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
        dto.setBranchId(u.getBranchId());
        dto.setBranchName(u.getBranchName());
        dto.setIsActive(u.getIsActive());
        dto.setLastLogin(u.getLastLogin());
        dto.setCreatedAt(u.getCreatedAt());
        return dto;
    }
}
