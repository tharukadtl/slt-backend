package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByPhone(String phone);

    Optional<User> findByPhoneNumber(String phoneNumber);

    @Query("SELECT u FROM User u WHERE u.phone = :phone OR u.phoneNumber = :phone")
    Optional<User> findByPhoneOrPhoneNumber(String phone);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByPhone(String phone);

    boolean existsByEmail(String email);

    List<User> findByRole(User.Role role);

    List<User> findByOpmcId(Long opmcId);

    List<User> findByOpmcIdAndRole(Long opmcId, User.Role role);

    // QA_Compliance_Consolidated_Report.md — KpiCalculationService.getTeamKpi previously loaded
    // the ENTIRE users table via findAll() and filtered by opmcId/role in memory.
    List<User> findByOpmcIdAndRoleIn(Long opmcId, List<User.Role> roles);

    List<User> findByIsActiveTrue();

    List<User> findByRoleAndIsActiveTrue(User.Role role);

    @Query("SELECT u FROM User u WHERE u.role = :role AND u.isActive = true AND u.opmcId = :opmcId")
    List<User> findActiveByRoleAndOpmc(User.Role role, Long opmcId);

    @Query("SELECT COUNT(u) FROM User u WHERE u.role = :role AND u.isActive = true")
    long countActiveByRole(User.Role role);
}
