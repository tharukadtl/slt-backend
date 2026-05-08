package lk.slt.fieldops.repository;

import lk.slt.fieldops.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByRecipientIdOrderByCreatedAtDesc(Long recipientId);

    List<Notification> findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(Long recipientId);

    long countByRecipientIdAndIsReadFalse(Long recipientId);

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.isRead = TRUE, n.readAt = CURRENT_TIMESTAMP " +
           "WHERE n.recipientId = :recipientId AND n.isRead = FALSE")
    void markAllAsRead(Long recipientId);
}
