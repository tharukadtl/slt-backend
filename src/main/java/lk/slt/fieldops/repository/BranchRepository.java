package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * BranchRepository — Spring Data JPA generates all SQL automatically.
 *
 * You get these for FREE without writing any SQL:
 *   branchRepository.save(branch)         → INSERT or UPDATE
 *   branchRepository.findById(1L)         → SELECT by id
 *   branchRepository.findAll()            → SELECT all
 *   branchRepository.delete(branch)       → DELETE
 *   branchRepository.count()              → SELECT COUNT(*)
 */
@Repository
public interface BranchRepository extends JpaRepository<Branch, Long> {

    /** Find a branch by its unique code (e.g. "CMB-01") */
    Optional<Branch> findByCode(String code);

    /** Check if a code already exists (for validation before save) */
    boolean existsByCode(String code);

    /** Get all ACTIVE branches — used in dropdowns across the system */
    List<Branch> findByStatus(Branch.BranchStatus status);

    /** Get all branches in a specific district */
    List<Branch> findByDistrict(String district);

    /** Get all branches of a certain type */
    List<Branch> findByBranchType(Branch.BranchType branchType);

    /** Search branches by name — used in the admin search box */
    @Query("SELECT b FROM Branch b WHERE LOWER(b.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Branch> searchByName(String keyword);

    /** Count active branches — used on the Super Admin dashboard */
    long countByStatus(Branch.BranchStatus status);
}
