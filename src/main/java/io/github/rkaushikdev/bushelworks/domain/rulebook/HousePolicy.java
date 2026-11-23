package io.github.rkaushikdev.bushelworks.domain.rulebook;

import io.github.rkaushikdev.bushelworks.ConfigurationError;
import io.github.rkaushikdev.bushelworks.domain.application.OverDelivery;
import io.github.rkaushikdev.bushelworks.domain.application.SelectionRule;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The choices a house makes once and then lives with.
 *
 * @param stateCode the two letter state the house files with
 * @param selectionRule which contract a delivery fills first
 * @param overDelivery what happens to bushels past the last contract
 * @param roundSettlementToCents whether a settlement's net is rounded to the cent
 * @param minimumSettlement the smallest net a run writes a cheque for
 * @param dockageReporting how measured dockage becomes the dockage a ticket settles on
 */
public record HousePolicy(
        String stateCode,
        SelectionRule selectionRule,
        OverDelivery overDelivery,
        boolean roundSettlementToCents,
        BigDecimal minimumSettlement,
        DockageReporting dockageReporting) {

    public HousePolicy {
        Objects.requireNonNull(stateCode, "stateCode");
        Objects.requireNonNull(selectionRule, "selectionRule");
        Objects.requireNonNull(overDelivery, "overDelivery");
        Objects.requireNonNull(dockageReporting, "dockageReporting");
        if (stateCode.length() != 2) {
            throw new ConfigurationError("state code '" + stateCode + "' should be two letters");
        }
        if (minimumSettlement.signum() < 0) {
            throw new ConfigurationError("minimum settlement cannot be negative");
        }
    }

    /** The conservative defaults: window order, overage stored, cents, no minimum, dockage as measured. */
    public static HousePolicy of(String stateCode) {
        return new HousePolicy(
                stateCode,
                SelectionRule.WINDOW,
                OverDelivery.STORE,
                true,
                new BigDecimal("0.00"),
                DockageReporting.AS_MEASURED);
    }

    public HousePolicy withSelectionRule(SelectionRule value) {
        return new HousePolicy(
                stateCode, value, overDelivery, roundSettlementToCents, minimumSettlement, dockageReporting);
    }

    public HousePolicy withOverDelivery(OverDelivery value) {
        return new HousePolicy(
                stateCode, selectionRule, value, roundSettlementToCents, minimumSettlement, dockageReporting);
    }

    public HousePolicy withMinimumSettlement(BigDecimal value) {
        return new HousePolicy(stateCode, selectionRule, overDelivery, roundSettlementToCents, value, dockageReporting);
    }

    public HousePolicy withDockageReporting(DockageReporting value) {
        return new HousePolicy(
                stateCode, selectionRule, overDelivery, roundSettlementToCents, minimumSettlement, value);
    }
}
