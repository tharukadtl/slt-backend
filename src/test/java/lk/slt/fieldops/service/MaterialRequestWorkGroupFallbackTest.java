package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.CreateUserRequest;
import lk.slt.fieldops.dto.MaterialRequestDTO;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.MaterialRequest;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.entity.WorkGroup;
import lk.slt.fieldops.repository.MaterialCategoryRepository;
import lk.slt.fieldops.repository.MaterialRepository;
import lk.slt.fieldops.repository.MaterialRequestRepository;
import lk.slt.fieldops.repository.OpmcRepository;
import lk.slt.fieldops.repository.RefreshTokenRepository;
import lk.slt.fieldops.repository.StockTransactionRepository;
import lk.slt.fieldops.repository.UserAuditLogRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupAllocationRepository;
import lk.slt.fieldops.repository.WorkGroupRepository;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RES-024 (05_RESOURCE_MGMT) — the no-Work-Group material-request fallback.
 *
 * <p><b>The mechanism (permanent, not what this fix changes).</b>
 * {@code MaterialRequestService.deductForApproval} draws from the requester's
 * {@code WorkGroupAllocation} when {@code request.getWorkGroupId()} is
 * non-null, and falls back to the pre-Stage-D direct-from-OPMC-pool
 * deduction otherwise. That fallback is a genuine, permanent, correct path
 * for ADMIN/SUPER_ADMIN self-requests (who are outside the Work Group
 * hierarchy entirely) — it is not being removed by this fix.
 *
 * <p><b>The actual gap (what this fix closes).</b> {@code
 * UserService.createUser}/{@code updateUser} never required a {@code
 * workgroupId} for TECHNICIAN/TEAM_LEAD — unlike {@code opmcId} (RES-018),
 * whose non-requiredness is an explicit, documented, reasoned exception for
 * roles (SUPER_ADMIN/CLIENT) that legitimately have no OPMC. There was no
 * equivalent reasoning for {@code workgroupId}: it was just silently
 * optional for every role, so any Admin-created TECHNICIAN/TEAM_LEAD with no
 * Work Group made the fallback above permanently reachable for every
 * material request that user ever submitted — bypassing Work Group
 * allocation governance entirely, per the org hierarchy in SRS 5.5.0 (v1.9).
 *
 * <p><b>Scope note vs. the xlsx Automation Mapping.</b> RES-024's own
 * Assertion Code column only constructs a {@code MaterialRequest} with
 * {@code workGroupId=null} directly and asserts the fallback branch/{@code
 * AccessDeniedException} boundary — both already correct and unaffected by
 * this fix (see Steps A/C below). The step that actually flips from failing
 * (gap open) to passing (gap closed) is upstream, in {@code UserService}'s
 * creation-time validation (Step B) — so this class exercises both services
 * together, in the same pure-Mockito unit style {@code
 * MaterialRequestServiceTest}/{@code JobServiceTest} already use for this
 * module (REST Assured is not a dependency here, and the observable contract
 * is a direct Java method call, not an HTTP round trip).
 *
 * <p>{@link #documentsUnenforcedBypass()} is the method named in RES-024's
 * Automation Mapping. Steps A and C are permanent regression guards (green
 * both before and after this fix). Step B is the fix's actual target: it
 * fails before the fix (documenting the gap is real) and passes after
 * (documenting the gap is closed) — same test, same method, re-run as-is.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialRequestWorkGroupFallbackTest {

    // ─── MaterialRequestService collaborators ──────────────────────────────
    @Mock private MaterialRequestRepository     materialRequestRepository;
    @Mock private MaterialRepository            materialRepository;
    @Mock private MaterialCategoryRepository    materialCategoryRepository;
    @Mock private StockTransactionRepository    stockTransactionRepository;
    @Mock private WebSocketEventPublisher       webSocketEventPublisher;
    @Mock private WorkGroupAllocationRepository workGroupAllocationRepository;
    @Mock private NotificationService           notificationService;

    // ─── UserService collaborators ──────────────────────────────────────────
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserAuditLogRepository userAuditLogRepository;
    @Mock private PasswordEncoder        passwordEncoder;
    @Mock private OpmcRepository         opmcRepository;
    @Mock private WorkGroupRepository    workGroupRepository;

    // ─── Shared ──────────────────────────────────────────────────────────────
    @Mock private UserRepository userRepository;

    private MaterialRequestService materialRequestService;
    private UserService            userService;

    private static final Long MATERIAL_ID       = 5L;
    private static final Long TECH_ID           = 5L;
    private static final Long ADMIN_ID          = 9L;
    private static final Long TEAM_LEAD_ID      = 11L;
    private static final Long SUPER_ADMIN_ID    = 1L;
    private static final Long FALLBACK_REQ_ID   = 77L;
    private static final int  QUANTITY          = 3;

    private final AtomicLong idSeq = new AtomicLong(200);

    @BeforeEach
    void setUp() {
        materialRequestService = new MaterialRequestService(materialRequestRepository,
            materialRepository, materialCategoryRepository, userRepository,
            stockTransactionRepository, webSocketEventPublisher, workGroupAllocationRepository,
            notificationService);

        userService = new UserService(userRepository, refreshTokenRepository,
            userAuditLogRepository, passwordEncoder, opmcRepository, workGroupRepository);

        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(idSeq.incrementAndGet());
            return u;
        });
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        Material material = new Material();
        material.setId(MATERIAL_ID);
        material.setName("Fibre Drop Cable");
        material.setSku("MAT-CBL-001");
        material.setUnit("m");
        material.setUnitPrice(BigDecimal.valueOf(120));
        material.setCurrentStock(BigDecimal.valueOf(20));
        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialRequestRepository.save(any(MaterialRequest.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        User admin = new User();
        admin.setId(ADMIN_ID);
        admin.setFullName("Admin Amelia");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        User superAdmin = new User();
        superAdmin.setId(SUPER_ADMIN_ID);
        superAdmin.setFullName("Super Sarath");
        superAdmin.setRole(User.Role.SUPER_ADMIN);
        when(userRepository.findById(SUPER_ADMIN_ID)).thenReturn(Optional.of(superAdmin));

        WorkGroup teamLeadWorkGroup = new WorkGroup();
        teamLeadWorkGroup.setId(41L);
        User teamLead = new User();
        teamLead.setId(TEAM_LEAD_ID);
        teamLead.setFullName("Lead Lakshan");
        teamLead.setRole(User.Role.TEAM_LEAD);
        teamLead.setWorkgroup(teamLeadWorkGroup);
        when(userRepository.findById(TEAM_LEAD_ID)).thenReturn(Optional.of(teamLead));
    }

    /** A PENDING request with no Work Group at all — the fallback case. */
    private MaterialRequest fallbackRequest() {
        MaterialRequest request = MaterialRequest.builder()
            .id(FALLBACK_REQ_ID)
            .requestNumber("MR-2026-00077")
            .requestedBy(TECH_ID)
            .requestedByName("Tech Nimal")
            .workGroupId(null)
            .status(MaterialRequest.RequestStatus.PENDING)
            .urgency("NORMAL")
            .itemsData(MATERIAL_ID + ":" + QUANTITY + ":0")
            .totalEstimatedCost(360.0)
            .totalApprovedCost(0.0)
            .build();
        when(materialRequestRepository.findById(FALLBACK_REQ_ID)).thenReturn(Optional.of(request));
        return request;
    }

    private CreateUserRequest createUserReq(String role, Long workgroupId) {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("res024_" + idSeq.incrementAndGet());
        req.setPassword("Passw0rd!");
        req.setFullName("Test User");
        req.setRole(role);
        req.setWorkgroupId(workgroupId);
        return req;
    }

    @Test
    void documentsUnenforcedBypass() {
        MaterialRequest requestForA = fallbackRequest();
        MaterialRequestDTO.ApproveRequest adminApproval =
            MaterialRequestDTO.ApproveRequest.builder().notifyRequester(false).build();

        assertAll("RES-024 — mechanism (A, C: permanent, unaffected by this fix) "
                + "vs. reachability (B: must flip open -> closed)",

            // ── A: ADMIN approving a null-workGroupId request takes the direct
            //    -pool branch, not a WorkGroupAllocation lookup. Permanent —
            //    ADMIN/SUPER_ADMIN self-requests genuinely need this path. ────
            () -> {
                materialRequestService.approveRequest(FALLBACK_REQ_ID, adminApproval, ADMIN_ID);
                assertNull(requestForA.getWorkGroupId(),
                    "Precondition: this request must genuinely have no Work Group");
                verify(workGroupAllocationRepository, never())
                    .findByWorkGroupIdAndMaterialId(any(), any());
                verify(materialRepository).save(argThat(m ->
                    m.getCurrentStock().intValue() == 17));
            },

            // ── C: a Team Lead may never approve a fallback-case request —
            //    only ADMIN/SUPER_ADMIN can reach it. Permanent — already
            //    independently verified correct, re-pinned here. ────────────
            () -> {
                fallbackRequest();
                MaterialRequestDTO.ApproveRequest teamLeadApproval =
                    MaterialRequestDTO.ApproveRequest.builder().notifyRequester(false).build();
                assertThrows(AccessDeniedException.class,
                    () -> materialRequestService.approveRequest(
                        FALLBACK_REQ_ID, teamLeadApproval, TEAM_LEAD_ID),
                    "A Team Lead must be refused when approving a null-workGroupId request");
            },

            // ── B: THE FIX'S TARGET. Creating a TECHNICIAN or TEAM_LEAD with
            //    no workgroupId must be refused — this is what makes A above
            //    unreachable for these two roles going forward. Before the
            //    fix, both calls SUCCEED (no exception) — that silent success
            //    IS the gap. After the fix, both must throw. ─────────────────
            () -> assertThrows(RuntimeException.class,
                () -> userService.createUser(createUserReq("TECHNICIAN", null), ADMIN_ID),
                "Creating a TECHNICIAN with no Work Group must now be refused (RES-024) "
                    + "— before this fix, it silently succeeded"),

            () -> assertThrows(RuntimeException.class,
                () -> userService.createUser(createUserReq("TEAM_LEAD", null), ADMIN_ID),
                "Creating a TEAM_LEAD with no Work Group must now be refused (RES-024) "
                    + "— before this fix, it silently succeeded")
        );
    }

    /**
     * Regression guard, mirroring RES-018's own Case B: roles that
     * legitimately have no Work Group in the org hierarchy (SUPER_ADMIN,
     * ADMIN, CLIENT — none of them belong to a Work Group) must remain
     * creatable without one, both before and after this fix. This is not a
     * blanket "workgroupId is now mandatory for everyone" change — only
     * TECHNICIAN/TEAM_LEAD are affected.
     */
    @Test
    void rolesWithoutWorkGroup_remainCreatableWithoutOne() {
        assertAll("SUPER_ADMIN/ADMIN/CLIENT have no Work Group in the org hierarchy "
                + "and must stay creatable without one",
            () -> assertDoesNotThrow(
                () -> userService.createUser(createUserReq("SUPER_ADMIN", null), SUPER_ADMIN_ID)),
            () -> assertDoesNotThrow(
                () -> userService.createUser(createUserReq("ADMIN", null), SUPER_ADMIN_ID)),
            () -> assertDoesNotThrow(
                () -> userService.createUser(createUserReq("CLIENT", null), ADMIN_ID))
        );
    }
}
