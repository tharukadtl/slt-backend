package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.WorkGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkGroupRepository extends JpaRepository<WorkGroup, Long> {

    @Query("SELECT w FROM WorkGroup w WHERE w.opmc.id = :opmcId AND w.isActive = true")
    List<WorkGroup> findActiveByOpmcId(@Param("opmcId") Long opmcId);

    @Query("SELECT w FROM WorkGroup w WHERE w.teamLead.id = :teamLeadId")
    List<WorkGroup> findByTeamLeadId(@Param("teamLeadId") Long teamLeadId);

    List<WorkGroup> findByIsActiveTrue();
}
