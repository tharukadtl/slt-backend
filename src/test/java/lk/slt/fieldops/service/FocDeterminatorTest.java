package lk.slt.fieldops.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sheet {@code 04_PAYMENT_FLOW} row PAY-017 (FR-12) — FOC vs chargeable must be determined by
 * business rules, not by whoever is filling in the form: an in-warranty item and an installation
 * material are free of charge, while customer-caused damage and a customer-requested upgrade are
 * billed.
 *
 * <p><b>Why this test resolves its subject reflectively.</b> The row calls
 * {@code focDet.determine(item).isFoc()} on a {@code FocDeterminator} collaborator that may not
 * exist. Importing it directly would make this file fail to <i>compile</i> — a test that never
 * runs and never returns a verdict. It is therefore resolved with {@link Class#forName}, the same
 * shape used for JOB-009 / JOB-016 on sheet {@code 03_JOB_LIFECYCLE}: the test always executes,
 * fails today naming exactly what is missing, and starts exercising the four real rules unchanged
 * once the determinator lands. It deliberately does not assert the feature's absence.</p>
 */
class FocDeterminatorTest {

    private static final String[] CANDIDATES = {
        "lk.slt.fieldops.service.FocDeterminator",
        "lk.slt.fieldops.service.FocDeterminationService",
        "lk.slt.fieldops.shared.FocDeterminator",
    };

    private static Class<?> resolveDeterminator() {
        for (String name : CANDIDATES) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException ignored) {
                // try the next candidate
            }
        }
        return null;
    }

    @Test
    void businessRules_warrantyVsDamage() {
        Class<?> determinator = resolveDeterminator();

        assertNotNull(determinator,
            "No FOC determinator exists anywhere in the backend. FOC vs chargeable is decided "
                + "entirely by the person filling in the mobile wizard: Step1Materials tags each "
                + "line 'FOC' or 'CHARGEABLE' by hand, PaymentSubmissionScreen sums the two "
                + "buckets client-side, and the server stores whatever arrives as "
                + "materialsFocTotal / materialsChargeableTotal without applying — or being able "
                + "to apply — a single rule. Nothing in the backend knows about warranty periods, "
                + "damage attribution, installation materials or customer-requested upgrades, so "
                + "two Team Leads can bill the same work differently and neither can be shown to "
                + "be wrong. Needed: a rule component that takes an item plus its context "
                + "(warranty end date, damage cause, item purpose) and returns the FOC decision, "
                + "invoked server-side in PaymentService.submitPayment rather than trusted from "
                + "the client.");

        Method determine = Arrays.stream(determinator.getMethods())
            .filter(m -> m.getName().equals("determine") && m.getParameterCount() == 1)
            .findFirst().orElse(null);
        assertNotNull(determine,
            determinator.getName() + " must expose determine(item); found: "
                + Arrays.toString(determinator.getMethods()));

        // Once it exists, the row's four rules are the contract:
        //   warranty     -> FOC          installation material -> FOC
        //   customer damage -> charged   customer upgrade      -> charged
        // They are asserted through the same reflective handle so this block compiles today.
        assertTrue(isFoc(determine, "WARRANTY"),
            "An item within its warranty period must be determined FOC");
        assertFalse(isFoc(determine, "CUSTOMER_DAMAGE"),
            "Customer-caused damage must be determined chargeable");
        assertTrue(isFoc(determine, "INSTALLATION"),
            "An installation material must be determined FOC");
        assertFalse(isFoc(determine, "CUSTOMER_UPGRADE"),
            "A customer-requested upgrade must be determined chargeable");
    }

    /** Invoke determine(item) for the given item kind and read the resulting isFoc() flag. */
    private static boolean isFoc(Method determine, String itemKind) {
        try {
            Object determinator = determine.getDeclaringClass()
                .getDeclaredConstructor().newInstance();
            Object result = determine.invoke(determinator, (Object) itemKind);
            Method isFoc = Arrays.stream(result.getClass().getMethods())
                .filter(m -> m.getName().equals("isFoc") || m.getName().equals("getIsFoc"))
                .findFirst().orElseThrow(() ->
                    new AssertionError("determine(...) result exposes no isFoc()"));
            return Boolean.TRUE.equals(isFoc.invoke(result));
        } catch (Exception e) {
            throw new AssertionError(
                "Could not evaluate the FOC rule for " + itemKind + ": " + e, e);
        }
    }
}
