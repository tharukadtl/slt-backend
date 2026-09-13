package lk.slt.fieldops.service;

import org.springframework.stereotype.Service;

/**
 * FocDeterminator — issue #28 / PAY-017 (FR-12): FOC vs chargeable decided by business rules,
 * not by whoever fills in the mobile wizard.
 *
 * <p>Pure rule component, no collaborators: an item within its warranty period or an installation
 * material is free of charge; customer-caused damage and a customer-requested upgrade are billed.
 * An unclassified or unrecognized item defaults to chargeable — the safe default, since treating
 * an unknown item as free-of-charge would silently under-bill.</p>
 */
@Service
public class FocDeterminator {

    public FocDecision determine(String classification) {
        if (classification == null) {
            return new FocDecision(false, "No classification provided; defaults to chargeable");
        }
        switch (classification.toUpperCase()) {
            case "WARRANTY":
                return new FocDecision(true, "Item is within its warranty period");
            case "INSTALLATION":
                return new FocDecision(true, "Installation materials are provided free of charge");
            case "CUSTOMER_DAMAGE":
                return new FocDecision(false, "Customer-caused damage is chargeable");
            case "CUSTOMER_UPGRADE":
                return new FocDecision(false, "Customer-requested upgrade is chargeable");
            default:
                return new FocDecision(false, "Unrecognized classification '" + classification + "'; defaults to chargeable");
        }
    }

    public record FocDecision(boolean isFoc, String reason) {}
}
