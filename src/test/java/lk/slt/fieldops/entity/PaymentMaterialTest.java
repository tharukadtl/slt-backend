package lk.slt.fieldops.entity;

import lk.slt.fieldops.service.PaymentService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sheet {@code 04_PAYMENT_FLOW} row PAY-014 (FR-10) — the payment↔material junction must be in
 * second normal form: it carries no attribute that depends on only part of its key, so the
 * material's name and price live on the {@code materials} table and reach a payment view through a
 * JOIN rather than being copied into the junction row.
 *
 * <p><b>Why this test resolves its subject reflectively.</b> The row is written against a
 * {@code PaymentMaterial} entity and {@code payService.getPaymentWithDetails(15L)}. Neither may
 * exist. A test that imported {@code PaymentMaterial} directly would fail to <i>compile</i>, i.e.
 * never run and never return a verdict — so the class is resolved with {@link Class#forName} and
 * the accessor with {@link Class#getMethods()}, exactly as JOB-009 / JOB-016 do on sheet
 * {@code 03_JOB_LIFECYCLE}. This test therefore always executes, fails today naming precisely
 * what is missing, and starts exercising the real normalisation checks unchanged the moment the
 * junction entity lands.</p>
 *
 * <p>It deliberately does <b>not</b> assert the absence of the entity — doing so would lock the
 * current denormalised shape in as correct behaviour.</p>
 */
class PaymentMaterialTest {

    private static final String[] CANDIDATE_ENTITY_NAMES = {
        "lk.slt.fieldops.entity.PaymentMaterial",
        "lk.slt.fieldops.entity.PaymentMaterialUsage",
    };

    private static Class<?> resolvePaymentMaterial() {
        for (String name : CANDIDATE_ENTITY_NAMES) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name) {
        return Arrays.stream(type.getMethods())
            .filter(m -> m.getName().equals(name))
            .findFirst().orElse(null);
    }

    @Test
    void noPartialDependency_2NFCompliant() throws Exception {
        Class<?> paymentMaterial = resolvePaymentMaterial();

        assertNotNull(paymentMaterial,
            "No payment↔material junction entity exists, so there is no junction to normalise. A "
                + "Payment stores the material side of a bill as TWO opaque BigDecimal columns — "
                + "Payment.materialsFocTotal and Payment.materialsChargeableTotal (see "
                + "SubmitPaymentRequest) — with no per-material rows at all. Consequences: the "
                + "materials that made up a bill cannot be listed, audited, or re-classified "
                + "individually (which is also why PAY-012's FOC override has nowhere to live), "
                + "and 2NF cannot be assessed because the relation does not exist. Note this is "
                + "NOT the same table as the technician's job-time MaterialUsage entity, which "
                + "records consumption against a JOB, is never linked to a payment, and never "
                + "moves stock (see JOB-010). Needed: a payment_materials table keyed "
                + "(payment_id, material_id) holding ONLY quantity, is_foc and unit_price_at_time, "
                + "with name/current price left on materials.");

        // ── 2NF: the junction must not copy the material's own attributes ────────
        var fieldNames = Arrays.stream(paymentMaterial.getDeclaredFields())
            .map(java.lang.reflect.Field::getName)
            .collect(Collectors.toSet());

        assertFalse(fieldNames.contains("materialName"),
            "PaymentMaterial.materialName is a partial dependency (it depends on material_id "
                + "alone, not the full (payment_id, material_id) key) — it belongs on materials. "
                + "Fields present: " + fieldNames);

        Method getMaterialName = findMethod(paymentMaterial, "getMaterialName");
        if (getMaterialName != null) {
            Object instance = paymentMaterial.getDeclaredConstructor().newInstance();
            assertNull(getMaterialName.invoke(instance),
                "A junction row must not carry the material's name of its own accord");
        }

        // ── The name must still be reachable, via a JOIN in the detail view ──────
        Method withDetails = findMethod(PaymentService.class, "getPaymentWithDetails");
        assertNotNull(withDetails,
            "PaymentService exposes no getPaymentWithDetails(...) — with the junction in place "
                + "there must be a read path that JOINs materials so the material name is "
                + "populated for display without being duplicated into the junction row.");
    }
}
