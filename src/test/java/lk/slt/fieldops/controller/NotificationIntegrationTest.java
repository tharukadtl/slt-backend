package lk.slt.fieldops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.slt.fieldops.config.JwtTokenProvider;
import lk.slt.fieldops.entity.Notification;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.NotificationRepository;
import lk.slt.fieldops.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 08_NOTIFICATIONS (FR-21) — the in-app notification list a user's bell icon reads: the unread
 * count on load (NOTIF-006) and marking one notification read (NOTIF-007).
 *
 * <p><b>Tool substitution.</b> The Tool column says REST Assured; it is not a dependency of this
 * module and adding a test framework is a project decision, not this suite's. MockMvc through the
 * real filter chain is the module's established convention (see {@link AttendanceIntegrationTest},
 * {@link BillDisputeAmendmentIntegrationTest}) and drives the identical path: real JWT filter, real
 * {@code @AuthenticationPrincipal} resolution, real service, real MySQL. The test is
 * {@code @Transactional} so its rows roll back.</p>
 *
 * <p><b>Endpoints and payloads vs. the sheet — four corrections, all verified against
 * {@link NotificationController} rather than assumed.</b></p>
 * <ul>
 *   <li>There is no {@code GET /api/notifications?recipientId=…&amp;isRead=false}. The recipient is
 *       never a query parameter — every read route resolves it from the JWT via
 *       {@code @AuthenticationPrincipal Long userId}, which is the correct design (a
 *       {@code recipientId} parameter would let any caller read anyone's notifications). The unread
 *       list is {@code GET /api/notifications/unread}.</li>
 *   <li>There is no {@code GET /api/notifications/unread-count}; the badge route is
 *       {@code GET /api/notifications/count}.</li>
 *   <li>Its body is not {@code {"count": 7}} — {@code NotificationCountDTO} is
 *       {@code {"unread": 7, "total": 9, "hasUnread": true}}. The row's {@code count} is asserted as
 *       {@code unread}, and {@code total} is asserted too since the fixture deliberately seeds read
 *       rows alongside the unread ones.</li>
 *   <li>The list routes return a bare JSON array, not a {@code Page}, so there is no
 *       {@code totalElements}; the array length is the equivalent assertion.</li>
 * </ul>
 *
 * <p>Both rows are seeded through the real {@link NotificationRepository} rather than by calling the
 * service, so what is being read back is genuinely a persisted row and not a value the same call
 * just produced.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationIntegrationTest {

    @Autowired private MockMvc                mvc;
    @Autowired private JwtTokenProvider       jwt;
    @Autowired private UserRepository         userRepo;
    @Autowired private NotificationRepository notificationRepo;
    @Autowired private ObjectMapper           json;

    /** Existing row every branch-scoped FK in this schema resolves against. */
    private static final Long REAL_BRANCH_ID = 1L;

    private static final int UNREAD_SEEDED = 7;
    private static final int READ_SEEDED   = 2;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private User recipient;

    @BeforeEach
    void seedRecipientWithNotifications() {
        long n = SEQ.incrementAndGet();
        User u = new User();
        u.setUsername("nu" + n);
        u.setPasswordHash("x");
        u.setFirstName("Notif");
        u.setLastName("Reader");
        u.setFullName("Notif Reader " + n);
        u.setPhone("07" + (10000000L + (n % 80000000L)));
        u.setRole(User.Role.TECHNICIAN);
        u.setOpmcId(REAL_BRANCH_ID);
        recipient = userRepo.save(u);

        for (int i = 1; i <= UNREAD_SEEDED; i++) {
            notificationRepo.save(notification(i, false));
        }
        // Read rows are seeded too, so "unread count" is genuinely a filter and not just a row count.
        for (int i = 1; i <= READ_SEEDED; i++) {
            notificationRepo.save(notification(100 + i, true));
        }
    }

    private Notification notification(int index, boolean read) {
        Notification n = new Notification();
        n.setRecipientId(recipient.getId());
        n.setType(Notification.NotificationType.FAULT_ASSIGNED);
        n.setTitle("Fault Assigned to You");
        n.setBody("Fault #FLT-2026-0" + (1000 + index) + " has been assigned to your team.");
        n.setReferenceId((long) (1000 + index));
        n.setReferenceType("FAULT");
        n.setIsRead(read);
        n.setIsPushSent(false);
        return n;
    }

    private String bearer(Long userId, String role) {
        return "Bearer " + jwt.createAccessToken(userId, "user" + userId, role, REAL_BRANCH_ID);
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult res = mvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, bearer(recipient.getId(), "TECHNICIAN")))
            .andReturn();
        assertEquals(200, res.getResponse().getStatus(),
            "GET " + path + " must return 200. Body: " + res.getResponse().getContentAsString());
        return json.readTree(res.getResponse().getContentAsString());
    }

    /**
     * NOTIF-006 — a user holding 7 unread notifications must see 7 in both the unread list and the
     * badge count.
     */
    @Test
    void unreadCount_returns7() throws Exception {
        // ── Steps 1-3: the unread list ───────────────────────────────────────────────────────
        JsonNode unread = getJson("/api/notifications/unread");
        assertTrue(unread.isArray(),
            "GET /api/notifications/unread returns a bare array, was: " + unread);
        assertEquals(UNREAD_SEEDED, unread.size(),
            "The unread list must hold exactly the 7 unread rows and none of the 2 read ones. "
                + "Body: " + unread);
        unread.forEach(n -> assertFalse(n.path("isRead").asBoolean(true),
            "Every row in the unread list must be unread: " + n));

        // ── Steps 4-5: the badge count ───────────────────────────────────────────────────────
        JsonNode count = getJson("/api/notifications/count");
        assertEquals(UNREAD_SEEDED, count.path("unread").asInt(-1),
            "GET /api/notifications/count must report 7 unread. Body: " + count);
        assertEquals(UNREAD_SEEDED + READ_SEEDED, count.path("total").asInt(-1),
            "total must count read rows too. Body: " + count);
        assertTrue(count.path("hasUnread").asBoolean(false),
            "hasUnread must be true while unread > 0. Body: " + count);

        // The full list must agree with both of the above.
        JsonNode all = getJson("/api/notifications");
        assertEquals(UNREAD_SEEDED + READ_SEEDED, all.size(),
            "GET /api/notifications must return every notification for the caller. Body: " + all);
    }

    /**
     * NOTIF-007 — marking one notification read must flip {@code isRead}, stamp {@code readAt}, and
     * decrement the badge count by exactly one.
     *
     * <p>The row names notification id 99. Ids are database-assigned here, so the test marks the
     * first of its own seeded unread rows and asserts on that id — the substance of the row (one
     * specific unread notification, by id) is unchanged.</p>
     */
    @Test
    void markRead_isReadTrue_countDecrements() throws Exception {
        List<Notification> unreadBefore =
            notificationRepo.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(recipient.getId());
        assertEquals(UNREAD_SEEDED, unreadBefore.size(), "Fixture precondition");
        Long targetId = unreadBefore.get(0).getId();

        int countBefore = getJson("/api/notifications/count").path("unread").asInt(-1);
        assertEquals(UNREAD_SEEDED, countBefore, "Fixture precondition: 7 unread before the PATCH");

        // ── Steps 1-2: PATCH /api/notifications/{id}/read ────────────────────────────────────
        MvcResult res = mvc.perform(patch("/api/notifications/" + targetId + "/read")
                .header(HttpHeaders.AUTHORIZATION, bearer(recipient.getId(), "TECHNICIAN")))
            .andReturn();

        assertEquals(200, res.getResponse().getStatus(),
            "PATCH /api/notifications/{id}/read must return 200. Body: "
                + res.getResponse().getContentAsString());

        // ── Steps 3-4: the response body reports the new state ───────────────────────────────
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertTrue(body.path("isRead").asBoolean(false),
            "The returned notification must be marked read. Body: " + body);
        assertFalse(body.path("readAt").isNull() || body.path("readAt").isMissingNode(),
            "readAt must be stamped when the notification is read. Body: " + body);
        assertEquals(targetId.longValue(), body.path("id").asLong(),
            "The response must be the notification that was marked. Body: " + body);

        // ── The persisted row, not just the response ─────────────────────────────────────────
        Notification persisted = notificationRepo.findById(targetId).orElseThrow();
        assertTrue(persisted.getIsRead(), "The database row must be flagged read, not just the DTO");
        assertNotNull(persisted.getReadAt(), "The database row must carry the readAt timestamp");

        // ── Step 5: the badge count decrements by exactly one ────────────────────────────────
        int countAfter = getJson("/api/notifications/count").path("unread").asInt(-1);
        assertEquals(countBefore - 1, countAfter,
            "The unread count must drop from " + countBefore + " to " + (countBefore - 1)
                + " after one notification is marked read, was " + countAfter);
    }

    /**
     * Supporting evidence for NOTIF-010, not one of the sheet's mapped rows — recorded here because
     * this is the class that owns the mark-read contract.
     *
     * <p>{@code NotificationController} maps the read route as {@code @PatchMapping("/{id}/read")}
     * only. The Web Admin portal's notifications page issues a <b>POST</b> to the same path
     * ({@code NotificationsPage.js}'s {@code markRead}), which therefore cannot succeed. This pins
     * down what the server really answers, so the Cypress spec for NOTIF-010 can mirror it exactly
     * rather than assume it.</p>
     *
     * <p><b>It answers 500, not 405</b>, which is a second (minor) defect of its own:
     * {@code GlobalExceptionHandler} has no handler for
     * {@code HttpRequestMethodNotSupportedException}, so a plain wrong-verb request falls into the
     * catch-all {@code Exception} branch and is returned as
     * {@code {"status":500,"error":"Internal Server Error","message":"An unexpected error
     * occurred. Please try again."}} — the server blames itself for a client mistake, and the
     * caller gets no hint that the route exists under a different verb. This method deliberately
     * asserts the correct 405 and is therefore <b>red</b>; it is supporting evidence, not one of
     * the sheet's rows, and does not affect the NOTIF-006/NOTIF-007 verdicts above.</p>
     */
    @Test
    void markReadRoute_rejectsPostVerb_evidenceForNotif010() throws Exception {
        Long targetId =
            notificationRepo.findByRecipientIdAndIsReadFalseOrderByCreatedAtDesc(recipient.getId())
                .get(0).getId();

        int postStatus = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/notifications/" + targetId + "/read")
                    .header(HttpHeaders.AUTHORIZATION, bearer(recipient.getId(), "TECHNICIAN")))
            .andReturn().getResponse().getStatus();

        assertNotEquals(200, postStatus,
            "POST is not mapped on this route — if it ever answers 200 this evidence note is stale");
        assertEquals(405, postStatus,
            "POST /api/notifications/{id}/read must be 405 Method Not Allowed; the route is "
                + "PATCH-only. Actual: " + postStatus);

        // And the notification is of course still unread afterwards.
        assertFalse(notificationRepo.findById(targetId).orElseThrow().getIsRead(),
            "A rejected POST must not have marked anything read");
    }
}
