package lk.slt.fieldops.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import lk.slt.fieldops.dto.MaterialRequestDTO;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.MaterialRequest;
import lk.slt.fieldops.entity.StockTransaction;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.MaterialCategoryRepository;
import lk.slt.fieldops.repository.MaterialRepository;
import lk.slt.fieldops.repository.MaterialRequestRepository;
import lk.slt.fieldops.repository.StockTransactionRepository;
import lk.slt.fieldops.repository.UserRepository;
import lk.slt.fieldops.repository.WorkGroupAllocationRepository;
import lk.slt.fieldops.websocket.WebSocketEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RES-003 and RES-005 (05_RESOURCE_MGMT, FR-13) — the two {@link MaterialRequestService} behaviours
 * the sheet specifies as unit tests: approving a request deducts stock and notifies the requester,
 * and rejecting one requires a reason.
 *
 * <p><b>Collaborator naming vs. the sheet.</b> The sheet mocks a {@code StockService} and expects
 * {@code verify(stockService).deduct(5L, 3)}. No such collaborator exists — {@link
 * MaterialRequestService} performs the deduction inline against {@code MaterialRepository} and
 * writes the ledger row itself via its own {@code saveStockTransaction} helper. The observable,
 * assertable contract is therefore the saved {@link Material} balance plus the {@link
 * StockTransaction} row, which is what RES-003 asserts. Likewise the sheet's
 * {@code notifService.sendRequestApprovedToTech(techId)} is really
 * {@code WebSocketEventPublisher.sendToUser(userId, ..., "MATERIAL_REQUEST_APPROVED")}.</p>
 *
 * <p><b>RES-005's "ValidationException".</b> No such type exists in this codebase, and
 * {@code rejectRequest} itself does not inspect the reason — the mandatory-reason rule is enforced
 * one layer out, as {@code @NotBlank} on {@link MaterialRequestDTO.RejectRequest#getReason()} with
 * {@code @Valid} on {@code InventoryController.rejectRequest}, which Spring turns into
 * {@code MethodArgumentNotValidException} → HTTP 400. That is a real, effective guard on every route
 * into the service, so this test drives it with a real Jakarta {@link Validator} rather than
 * asserting an exception type that cannot exist. The service half (reason stored, requester told
 * why) is asserted directly.</p>
 *
 * <p>Pure Mockito unit test (no Spring, no MySQL), matching the module convention established by
 * {@code JobServiceTest} / {@code JobServiceBodDuplicateTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialRequestServiceTest {

    @Mock private MaterialRequestRepository  materialRequestRepository;
    @Mock private MaterialRepository         materialRepository;
    @Mock private MaterialCategoryRepository materialCategoryRepository;
    @Mock private UserRepository             userRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private WebSocketEventPublisher    webSocketEventPublisher;
    @Mock private WorkGroupAllocationRepository workGroupAllocationRepository;
    @Mock private NotificationService        notificationService;

    private MaterialRequestService requestService;

    private static final Long REQUEST_ID  = 77L;
    private static final Long MATERIAL_ID = 5L;
    private static final Long TECH_ID     = 5L;
    private static final Long ADMIN_ID    = 9L;
    private static final int  QUANTITY    = 3;

    private Material material;

    @BeforeEach
    void setUp() {
        requestService = new MaterialRequestService(materialRequestRepository, materialRepository,
            materialCategoryRepository, userRepository, stockTransactionRepository,
            webSocketEventPublisher, workGroupAllocationRepository, notificationService);

        User technician = new User();
        technician.setId(TECH_ID);
        technician.setFullName("Tech Nimal");
        technician.setRole(User.Role.TECHNICIAN);
        when(userRepository.findById(TECH_ID)).thenReturn(Optional.of(technician));

        User admin = new User();
        admin.setId(ADMIN_ID);
        admin.setFullName("Admin Amelia");
        admin.setRole(User.Role.ADMIN);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        material = new Material();
        material.setId(MATERIAL_ID);
        material.setName("Fibre Drop Cable");
        material.setSku("MAT-CBL-001");
        material.setUnit("m");
        material.setUnitPrice(BigDecimal.valueOf(120));
        material.setMinimumThreshold(BigDecimal.TEN);
        material.setCurrentStock(BigDecimal.valueOf(20));

        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialRequestRepository.save(any(MaterialRequest.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        when(stockTransactionRepository.save(any(StockTransaction.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    /** A PENDING request for {@value #QUANTITY} of {@link #MATERIAL_ID}, in the real itemsData shape. */
    private MaterialRequest pendingRequest() {
        MaterialRequest request = MaterialRequest.builder()
            .id(REQUEST_ID)
            .requestNumber("MR-2026-00077")
            .requestedBy(TECH_ID)
            .requestedByName("Tech Nimal")
            .status(MaterialRequest.RequestStatus.PENDING)
            .urgency("HIGH")
            .itemsData(MATERIAL_ID + ":" + QUANTITY + ":0")
            .totalEstimatedCost(360.0)
            .totalApprovedCost(0.0)
            .build();
        when(materialRequestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(request));
        return request;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-003 — admin approves request, stock auto-deducted
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void approveRequest_stockDeducted() {
        // ── Arrange: a PENDING request for 3 m of a material with 20 m on hand ────────────────
        MaterialRequest request = pendingRequest();

        MaterialRequestDTO.ApproveRequest approval = MaterialRequestDTO.ApproveRequest.builder()
            .notes("Approved — in stock")
            .notifyRequester(true)
            .build();

        // ── Act: step 1 ──────────────────────────────────────────────────────────────────────
        MaterialRequestDTO.RequestResponse response =
            requestService.approveRequest(REQUEST_ID, approval, ADMIN_ID);

        assertAll("approving a material request deducts stock and tells the requester",

            // ── Step 2: the request itself is APPROVED, by the reviewing admin ──────────────
            () -> assertEquals("APPROVED", response.getStatus(),
                "The reviewed request must come back APPROVED"),
            () -> assertEquals(MaterialRequest.RequestStatus.APPROVED, request.getStatus()),
            () -> assertEquals(ADMIN_ID, request.getReviewedBy(),
                "The approving admin must be recorded on the request"),
            () -> assertNotNull(request.getReviewedAt(), "The review must be timestamped"),

            // ── Steps 3-4: stock moves, and by exactly the approved quantity ───────────────
            () -> assertEquals(17, material.getCurrentStock().intValue(),
                "20 on hand minus the 3 approved must leave 17"),
            () -> {
                ArgumentCaptor<StockTransaction> tx =
                    ArgumentCaptor.forClass(StockTransaction.class);
                verify(stockTransactionRepository, atLeastOnce()).save(tx.capture());
                StockTransaction row = tx.getValue();

                assertEquals(MATERIAL_ID, row.getMaterialId(),
                    "The ledger row must be against the approved material");
                assertEquals(QUANTITY, row.getQuantity().intValue(),
                    "The ledger row must record the approved quantity");
                assertEquals(StockTransaction.TransactionType.STOCK_OUT, row.getTransactionType(),
                    "Issuing approved materials is a stock-out movement");
                assertEquals(ADMIN_ID, row.getPerformedBy(),
                    "The ledger must attribute the movement to the approving admin");
                assertTrue(row.getReference() != null
                        && row.getReference().contains(request.getRequestNumber()),
                    "The ledger row must reference the request it came from, was: "
                        + row.getReference());
            },

            // ── The approved cost must be priced off the real unit price ───────────────────
            () -> assertEquals(360.0, response.getTotalApprovedCost(), 0.001,
                "3 m at LKR 120 is LKR 360"),

            // ── Step 5: the requesting technician is notified ──────────────────────────────
            () -> verify(webSocketEventPublisher).sendToUser(
                eq(String.valueOf(TECH_ID)), any(), any(), eq("MATERIAL_REQUEST_APPROVED"))
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-005 — reject material request with a mandatory reason
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void rejectRequest_reasonMandatory() {
        // ── Arrange ──────────────────────────────────────────────────────────────────────────
        MaterialRequest request = pendingRequest();

        MaterialRequestDTO.RejectRequest noReason = MaterialRequestDTO.RejectRequest.builder()
            .notes("no reason given")
            .notifyRequester(true)
            .build();

        MaterialRequestDTO.RejectRequest withReason = MaterialRequestDTO.RejectRequest.builder()
            .reason("Out of stock")
            .notifyRequester(true)
            .build();

        assertAll("a rejection must carry a reason, and that reason must reach the requester",

            // ── Step 1: a null reason is refused before it can reach the service ───────────
            () -> {
                try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                    Validator validator = factory.getValidator();

                    Set<ConstraintViolation<MaterialRequestDTO.RejectRequest>> violations =
                        validator.validate(noReason);
                    assertFalse(violations.isEmpty(),
                        "A rejection with no reason must be rejected by validation — "
                            + "InventoryController.rejectRequest declares @Valid on this body");
                    assertTrue(violations.stream()
                            .anyMatch(v -> "reason".equals(v.getPropertyPath().toString())),
                        "The violation must be on the 'reason' field, was: " + violations);

                    assertTrue(validator.validate(withReason).isEmpty(),
                        "A rejection that does carry a reason must pass validation");
                }
            },

            // ── Steps 2-3: with a reason, the request is REJECTED and the reason stored ────
            () -> {
                MaterialRequestDTO.RequestResponse response =
                    requestService.rejectRequest(REQUEST_ID, withReason, ADMIN_ID);

                assertEquals("REJECTED", response.getStatus(),
                    "A rejected request must come back REJECTED");
                assertEquals("Out of stock", request.getRejectionReason(),
                    "The rejection reason must be persisted on the request");
                assertEquals("Out of stock", response.getRejectionReason(),
                    "The rejection reason must be returned to the caller");
                assertEquals(ADMIN_ID, request.getReviewedBy());
            },

            // ── Step 4: the requester is told, and told why ────────────────────────────────
            () -> {
                ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
                verify(webSocketEventPublisher).sendToUser(
                    eq(String.valueOf(TECH_ID)), any(), body.capture(),
                    eq("MATERIAL_REQUEST_REJECTED"));
                assertTrue(body.getValue().contains("Out of stock"),
                    "The requester must be told why it was rejected, was: " + body.getValue());
            }
        );
    }
}
