package lk.slt.fieldops.service;

import lk.slt.fieldops.dto.StockDTO;
import lk.slt.fieldops.entity.Material;
import lk.slt.fieldops.entity.StockTransaction;
import lk.slt.fieldops.entity.User;
import lk.slt.fieldops.repository.MaterialCategoryRepository;
import lk.slt.fieldops.repository.MaterialRepository;
import lk.slt.fieldops.repository.StockTransactionRepository;
import lk.slt.fieldops.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RES-004, RES-010 and RES-013 (05_RESOURCE_MGMT, FR-13) — the three stock-ledger behaviours the
 * sheet specifies as unit tests: a low-stock alert on breaching the minimum threshold, a running
 * balance across a four-transaction ledger, and the below-zero guard.
 *
 * <p><b>Class under test vs. the sheet.</b> The sheet names a {@code StockService} with
 * {@code deductStock(materialId, qty)} / {@code getCurrentBalance(matId)}. Neither exists. The real
 * class is {@link StockManagementService} and it has a single signed entry point,
 * {@code adjustStock(AdjustRequest, adminId)}, whose {@code quantityChange} is negative for a
 * deduction and positive for a restock/return (see {@code InventoryController} POST
 * {@code /api/inventory/stock/adjust}). The current balance is {@code Material.currentStock}, which
 * that method maintains — there is no separate balance query. These tests drive the real entry
 * point; the file keeps the sheet's mapped class name {@code StockServiceTest}.</p>
 *
 * <p><b>Exception type.</b> The sheet asserts {@code InsufficientStockException}. No such type
 * exists in this codebase — {@link StockManagementService} signals the below-zero guard with a plain
 * {@link RuntimeException} whose message starts {@code "Insufficient stock."}, which
 * {@code GlobalExceptionHandler.handleRuntime} maps to HTTP 400, the same contract the sheet's
 * Expected Result describes. These tests assert the exception AND its message, so they still fail if
 * the guard silently disappears. Same convention as {@code JobServiceTest}.</p>
 *
 * <p><b>Alert mechanism.</b> The sheet expects an {@code alertService.sendLowStockAlert(materialId)}
 * plus a row in the notifications table. The implemented alert is a WebSocket broadcast to the admin
 * role ({@code WebSocketEventPublisher.sendToRole(..., "STOCK_ALERT")}) plus the
 * {@code lowStockAlert} boolean on the response. The row-in-notifications half is asserted
 * separately in RES-015 ({@code NotificationServiceTest}), so this test asserts the mechanism that
 * actually exists and RES-015 carries the persistence gap.</p>
 *
 * <p>Pure Mockito unit test (no Spring, no MySQL), matching the module convention established by
 * {@code JobServiceTest} / {@code JobServiceBodDuplicateTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockServiceTest {

    @Mock private MaterialRepository         materialRepository;
    @Mock private MaterialCategoryRepository materialCategoryRepository;
    @Mock private StockTransactionRepository stockTransactionRepository;
    @Mock private UserRepository             userRepository;
    @Mock private WebSocketEventPublisher    webSocketEventPublisher;
    @Mock private NotificationService        notificationService;

    private StockManagementService stockService;

    private static final Long MATERIAL_ID = 5L;
    private static final Long ADMIN_ID    = 9L;

    /** The one Material instance every stub hands back, so stock changes accumulate across calls. */
    private Material material;

    @BeforeEach
    void setUp() {
        stockService = new StockManagementService(materialRepository, materialCategoryRepository,
            stockTransactionRepository, userRepository, webSocketEventPublisher,
            notificationService);

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
        material.setCurrentStock(BigDecimal.ZERO);

        when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
        when(materialRepository.save(any(Material.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stockTransactionRepository.save(any(StockTransaction.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    /** Builds the real request DTO the endpoint takes; {@code change} is signed. */
    private StockDTO.AdjustRequest adjust(int change, String type, String reason) {
        return StockDTO.AdjustRequest.builder()
            .materialId(MATERIAL_ID)
            .quantityChange(change)
            .transactionType(type)
            .reason(reason)
            .build();
    }

    private int currentBalance() {
        return material.getCurrentStock().intValue();
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-004 — low stock alert triggered below min threshold
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void deductStock_belowMin_triggersAlert() {
        // ── Arrange: 12 on hand, minimum threshold 10 ─────────────────────────────────────────
        material.setCurrentStock(BigDecimal.valueOf(12));
        material.setMinimumThreshold(BigDecimal.TEN);

        // ── Act, step 1: deduct 3 → 9, i.e. below the minimum ─────────────────────────────────
        StockDTO.AdjustResponse belowMin =
            stockService.adjustStock(adjust(-3, "STOCK_OUT", "Issued to job #1"), ADMIN_ID);

        assertEquals(9, belowMin.getNewStock().intValue(), "Precondition: the deduction must land on 9");

        assertAll("stock dropping below the minimum threshold raises a low-stock alert",

            // ── Step 2: the alert fires ───────────────────────────────────────────────────────
            () -> assertTrue(belowMin.isLowStockAlert(),
                "Stock 9 is below the minimum of 10 — the response must flag a low-stock alert"),

            // ── Step 3: the alert is actually pushed, naming the material ────────────────────
            () -> {
                ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
                verify(webSocketEventPublisher, atLeastOnce())
                    .sendToRole(eq("admin"), any(), body.capture(), eq("STOCK_ALERT"));
                assertTrue(body.getValue().contains(material.getName()),
                    "The alert must name the material that ran low, was: " + body.getValue());
            },

            // ── Step 3b: LOW_STOCK is reflected in the status vocabulary ─────────────────────
            () -> assertEquals(Material.StockStatus.LOW_STOCK.name(), belowMin.getStockStatus(),
                "9 of a minimum 10 is LOW_STOCK, not IN_STOCK"),

            // ── Step 4: restocking back above the minimum raises no further alert ────────────
            () -> {
                clearInvocations(webSocketEventPublisher);
                StockDTO.AdjustResponse aboveMin =
                    stockService.adjustStock(adjust(+2, "RESTOCK", "Restocked"), ADMIN_ID);

                assertEquals(11, aboveMin.getNewStock().intValue(), "Precondition: the restock must land on 11");
                assertFalse(aboveMin.isLowStockAlert(),
                    "Stock 11 is above the minimum of 10 — no low-stock alert is due");
                verify(webSocketEventPublisher, never())
                    .sendToRole(any(), any(), any(), eq("STOCK_ALERT"));
            }
        );
    }

    /**
     * QA_Compliance_Consolidated_Report.md — Stage G FCM Major: low stock was WebSocket-only,
     * and {@code NotificationService.notifyLowStock} had zero callers anywhere (and, even once
     * wired in, was itself in-app-only — no push). {@code deductStock_belowMin_triggersAlert}
     * above already asserts the WebSocket mechanism; this asserts the durable one, addressed to
     * every active admin, added alongside it.
     */
    @Test
    void deductStock_belowMin_notifiesEveryActiveAdmin() {
        material.setCurrentStock(BigDecimal.valueOf(12));
        material.setMinimumThreshold(BigDecimal.TEN);

        User admin2 = new User();
        admin2.setId(ADMIN_ID + 1);
        admin2.setFullName("Admin Bandara");
        admin2.setRole(User.Role.ADMIN);
        admin2.setFcmToken("fcm-admin-2");

        User admin1 = userRepository.findById(ADMIN_ID).get();
        when(userRepository.findByRoleAndIsActiveTrue(User.Role.ADMIN))
            .thenReturn(List.of(admin1, admin2));
        when(userRepository.findByRoleAndIsActiveTrue(User.Role.SUPER_ADMIN))
            .thenReturn(List.of());

        stockService.adjustStock(adjust(-3, "STOCK_OUT", "Issued to job #1"), ADMIN_ID);

        assertAll("a low-stock crossing notifies every active admin, not just whoever is on socket",
            () -> verify(notificationService).notifyLowStock(
                eq(ADMIN_ID), any(), eq(material.getName()), eq(MATERIAL_ID)),
            () -> verify(notificationService).notifyLowStock(
                eq(admin2.getId()), eq("fcm-admin-2"), eq(material.getName()), eq(MATERIAL_ID))
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-010 — stock transaction ledger, running balance
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void runningBalance_fourTransactions_correct() {
        // ── Arrange: empty shelf, and a ledger that records everything written to it ──────────
        material.setCurrentStock(BigDecimal.ZERO);

        List<StockTransaction> ledger = new ArrayList<>();
        when(stockTransactionRepository.save(any(StockTransaction.class))).thenAnswer(inv -> {
            StockTransaction tx = inv.getArgument(0);
            ledger.add(tx);
            return tx;
        });

        // ── Act: the sheet's four operations, +50, -10, -5, +3 ───────────────────────────────
        StockDTO.AdjustResponse purchase =
            stockService.adjustStock(adjust(+50, "RESTOCK", "Purchase order PO-1001"), ADMIN_ID);
        StockDTO.AdjustResponse usage1 =
            stockService.adjustStock(adjust(-10, "USAGE", "Used in job #1"), ADMIN_ID);
        StockDTO.AdjustResponse usage2 =
            stockService.adjustStock(adjust(-5, "USAGE", "Used in job #2"), ADMIN_ID);
        StockDTO.AdjustResponse returned =
            stockService.adjustStock(adjust(+3, "RETURN", "Returned unused from job #2"), ADMIN_ID);

        assertAll("a four-transaction ledger leaves a correct running balance",

            // ── Steps 1-4: each step lands on the balance the sheet states ───────────────────
            () -> assertEquals(50, purchase.getNewStock().intValue(), "+50 on an empty shelf is 50"),
            () -> assertEquals(40, usage1.getNewStock().intValue(),   "50 - 10 is 40"),
            () -> assertEquals(35, usage2.getNewStock().intValue(),   "40 - 5 is 35"),
            () -> assertEquals(38, returned.getNewStock().intValue(), "35 + 3 is 38"),

            // ── Step 5: four ledger rows, each of the right type ────────────────────────────
            () -> assertEquals(4, ledger.size(),
                "Every adjustment must leave exactly one stock_transactions row"),
            () -> assertEquals(
                List.of(StockTransaction.TransactionType.RESTOCK,
                        StockTransaction.TransactionType.USAGE,
                        StockTransaction.TransactionType.USAGE,
                        StockTransaction.TransactionType.RETURN),
                ledger.stream().map(StockTransaction::getTransactionType).toList(),
                "The ledger must record the type of each movement, in order"),

            // ── The ledger's before/after must chain, so the balance is reconstructable ──────
            () -> assertEquals(List.of(0, 50, 40, 35),
                ledger.stream().map(t -> t.getStockBefore().intValue()).toList(),
                "Each row's stockBefore must equal the previous row's stockAfter"),
            () -> assertEquals(List.of(50, 40, 35, 38),
                ledger.stream().map(t -> t.getStockAfter().intValue()).toList(),
                "Each row's stockAfter must be the balance at that point"),

            // ── Step 6: the material's own balance agrees with the ledger ───────────────────
            () -> assertEquals(38, currentBalance(),
                "The material's on-hand stock must equal the ledger's closing balance")
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    // RES-013 — stock below 0 prevented
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    @Test
    void stockBelowZero_throwsInsufficientStock() {
        // ── Arrange: exactly 5 on hand ────────────────────────────────────────────────────────
        material.setCurrentStock(BigDecimal.valueOf(5));

        // ── Act, step 1: deducting all 5 is allowed and lands on exactly zero ─────────────────
        StockDTO.AdjustResponse toZero =
            stockService.adjustStock(adjust(-5, "STOCK_OUT", "Issued to job #7"), ADMIN_ID);

        assertAll("zero is a legal balance, below zero is not",

            () -> assertEquals(0, toZero.getNewStock().intValue(), "Deducting the last 5 must be allowed"),
            () -> assertEquals(Material.StockStatus.OUT_OF_STOCK.name(), toZero.getStockStatus(),
                "A zero balance is OUT_OF_STOCK"),

            // ── Step 2: one more unit off an empty shelf must be refused ────────────────────
            () -> {
                RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> stockService.adjustStock(
                        adjust(-1, "STOCK_OUT", "Issued to job #8"), ADMIN_ID),
                    "Deducting below zero must be refused");

                assertTrue(thrown.getMessage().startsWith("Insufficient stock."),
                    "The refusal must say why; was: " + thrown.getMessage());
            },

            // ── And the refused attempt must not have moved anything ────────────────────────
            () -> assertEquals(0, currentBalance(),
                "A refused deduction must leave the balance untouched")
        );
    }
}
