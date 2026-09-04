package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.ConfirmedResourcePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConfirmedResourcePlanRepository extends JpaRepository<ConfirmedResourcePlan, Long> {

    List<ConfirmedResourcePlan> findByOpmcIdAndPlanDate(Long opmcId, LocalDate planDate);

    Optional<ConfirmedResourcePlan> findByOpmcIdAndPlanDateAndShift(
            Long opmcId, LocalDate planDate, ConfirmedResourcePlan.Shift shift);
}
