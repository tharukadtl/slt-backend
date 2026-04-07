package lk.slt.fieldops.auth.repository;

import lk.slt.fieldops.auth.entity.OtpRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface OtpRecordRepository extends JpaRepository<OtpRecord, Long> {

    @Query("SELECT o FROM OtpRecord o WHERE o.phoneNumber = :phoneNumber " +
           "AND o.isUsed = false AND o.expiresAt > :now " +
           "ORDER BY o.createdAt DESC LIMIT 1")
    Optional<OtpRecord> findLatestValidOtp(String phoneNumber, LocalDateTime now);

    @Query("SELECT COUNT(o) FROM OtpRecord o WHERE o.phoneNumber = :phoneNumber " +
           "AND o.createdAt > :since")
    long countRecentOtps(String phoneNumber, LocalDateTime since);
}
