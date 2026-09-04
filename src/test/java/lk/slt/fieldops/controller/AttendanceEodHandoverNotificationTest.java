package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Fault;
import lk.slt.fieldops.entity.Job;
import lk.slt.fieldops.entity.Notification;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.FaultRepository;
import lk.slt.fieldops.repository.JobRepository;
import lk.slt.fieldops.repository.NotificationRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA_Compliance_Consolidated_Report.md — the technician EOD pending-job handover
 * ({@code AttendanceService.checkOut}) set {@code eodHandoverReason}/{@code eodHandoverAt} on the
 * Job and reset the linked Fault's status, but never told the Team Lead who now owns reassigning
 * it — no FCM push, no persisted {@code notifications} row, nothing. Fixed by wiring in
 * {@code NotificationService.notifyJobEodHandoverToTeamLead}, a new method mirroring the existing
 * {@code notifyJobRejectedToTeamLead} call shape (mirrored, not reused, since a handover is not a
 * rejection).
 *
 * <p>Real fixture, real MockMvc through the actual filter chain, {@code @Transactional} rollback —
 * this project's established convention.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AttendanceEodHandoverNotificationTest {

    @Autowired private MockMvc                mvc;
    @Autowired private JwtTokenProvider       jwt;
    @Autowired private UserRepository         userRepo;
    @Autowired private JobRepository          jobRepo;
    @Autowired private FaultRepository        faultRepo;
    @Autowired private NotificationRepository notificationRepo;
    @Autowired private ObjectMapper           json;

    private static final Long REAL_OPMC_ID = 1L;
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());
    private long uniq() { return SEQ.incrementAndGet(); }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_OPMC_ID);
    }

    private User newUser(User.Role role, String fullName) {
        long n = uniq();
        User u = new User();
        u.setUsername("aehn" + n);
        u.setPasswordHash("x");
        u.setFirstName("Aehn");
        u.setLastName(role.name());
        u.setFullName(fullName);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(role);
        u.setOpmcId(REAL_OPMC_ID);
        u.setFcmToken("fcm-token-" + n);
        return userRepo.save(u);
    }

    @Test
    void eodHandover_notifiesTeamLead() throws Exception {
        User teamLead   = newUser(User.Role.TEAM_LEAD, "Handover TL");
        User technician = newUser(User.Role.TECHNICIAN, "Handover Tech");
        User customer   = newUser(User.Role.CLIENT, "Handover Client");

        Fault fault = new Fault();
        fault.setFaultNumber("FLT-EODH-" + uniq());
        fault.setOpmcId(REAL_OPMC_ID);
        fault.setCustomerId(customer.getId());
        fault.setCustomerName(customer.getFullName());
        fault.setCategory(Fault.FaultCategory.INTERNET);
        fault.setDescription("EOD handover notification fixture");
        fault.setLocationAddress("Colombo 03");
        fault.setPriority(Fault.FaultPriority.MEDIUM);
        fault.setStatus(Fault.FaultStatus.IN_PROGRESS);
        fault = faultRepo.save(fault);

        Job job = new Job();
        job.setJobNumber("JOB-EODH-" + uniq());
        job.setFaultId(fault.getId());
        job.setFaultNumber(fault.getFaultNumber());
        job.setCustomerId(customer.getId());
        job.setCustomerName(customer.getFullName());
        job.setTeamLeadId(teamLead.getId());
        job.setTeamLeadName(teamLead.getFullName());
        job.setTechnicianId(technician.getId());
        job.setTechnicianName(technician.getFullName());
        job.setStatus(Job.JobStatus.IN_PROGRESS);
        job.setPriority(Job.JobPriority.MEDIUM);
        job.setScheduledDate(LocalDate.now());
        job = jobRepo.save(job);

        // Real check-in first — checkout requires an active session.
        mvc.perform(post("/api/attendance/check-in")
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\":6.9271,\"longitude\":79.8612}"))
            .andReturn();

        String reason = "Material shortage, resuming tomorrow";
        String body = "{\"openJobReasons\":[{\"jobId\":" + job.getId()
            + ",\"reason\":" + json.writeValueAsString(reason) + "}]}";

        MvcResult res = mvc.perform(post("/api/attendance/check-out")
                .header("Authorization", bearer(technician.getId(), "TECHNICIAN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "Check-out with a handover reason for the one open job must succeed. Body: "
                + res.getResponse().getContentAsString());

        List<Notification> teamLeadNotifications =
            notificationRepo.findByRecipientIdOrderByCreatedAtDesc(teamLead.getId());

        Long jobId = job.getId();
        Notification handoverNotification = teamLeadNotifications.stream()
            .filter(n -> jobId.equals(n.getReferenceId()) && "JOB".equals(n.getReferenceType()))
            .findFirst()
            .orElse(null);

        assertNotNull(handoverNotification,
            "The Team Lead must receive a persisted notification when a technician hands off a "
                + "pending job at EOD. AttendanceService.checkOut set eodHandoverReason/eodHandoverAt "
                + "and reset the fault's status but dispatched no notification at all. Rows for this "
                + "Team Lead: " + teamLeadNotifications);

        assertEquals(Notification.NotificationType.JOB_ON_HOLD, handoverNotification.getType(),
            "An EOD handover is not a rejection — it should not be typed/worded as one. Was: "
                + handoverNotification.getType());
        assertTrue(handoverNotification.getBody().contains(reason),
            "The notification must carry the technician's actual handover reason. Body: "
                + handoverNotification.getBody());
        assertFalse(handoverNotification.getIsRead(),
            "A freshly-created notification must start unread.");

        // The pre-existing halves of this fix must still work, unchanged.
        Job reloaded = jobRepo.findById(job.getId()).orElseThrow();
        assertEquals(reason, reloaded.getEodHandoverReason(),
            "The job's own eodHandoverReason must still be set, exactly as before this fix.");
        assertNotNull(reloaded.getEodHandoverAt(),
            "The job's own eodHandoverAt must still be stamped, exactly as before this fix.");
        assertEquals(Job.JobStatus.PENDING, reloaded.getStatus(),
            "The job must still return to the pending pool, exactly as before this fix.");

        Fault reloadedFault = faultRepo.findById(fault.getId()).orElseThrow();
        assertEquals(Fault.FaultStatus.ASSIGNED, reloadedFault.getStatus(),
            "The linked fault must still be pulled back to ASSIGNED, exactly as before this fix.");
    }
}
