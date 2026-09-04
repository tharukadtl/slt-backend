package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Opmc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * OpmcRepository — Spring Data JPA generates all SQL automatically.
 * Formerly BranchRepository — renamed as part of the OPMC restructure (Stage B).
 *
 * You get these for FREE without writing any SQL:
 *   opmcRepository.save(opmc)         → INSERT or UPDATE
 *   opmcRepository.findById(1L)       → SELECT by id
 *   opmcRepository.findAll()          → SELECT all
 *   opmcRepository.delete(opmc)       → DELETE
 *   opmcRepository.count()            → SELECT COUNT(*)
 */
@Repository
public interface OpmcRepository extends JpaRepository<Opmc, Long> {

    /** Find an OPMC by its unique code (e.g. "CMB-01") */
    Optional<Opmc> findByCode(String code);

    /** Check if a code already exists (for validation before save) */
    boolean existsByCode(String code);

    /** Get all ACTIVE OPMCs — used in dropdowns across the system */
    List<Opmc> findByStatus(Opmc.OpmcStatus status);

    /** Get all OPMCs in a specific district */
    List<Opmc> findByDistrict(String district);

    /** Get all OPMCs of a certain type */
    List<Opmc> findByOpmcType(Opmc.OpmcType opmcType);

    /** Search OPMCs by name — used in the admin search box */
    @Query("SELECT o FROM Opmc o WHERE LOWER(o.name) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Opmc> searchByName(String keyword);

    /** Count active OPMCs — used on the Super Admin dashboard */
    long countByStatus(Opmc.OpmcStatus status);
}
