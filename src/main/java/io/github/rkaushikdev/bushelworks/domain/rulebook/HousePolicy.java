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
 */
public record HousePolicy(
        String stateCode,
        SelectionRule selectionRule,
        OverDelivery overDelivery,
        boolean roundSettlementToCents,
        BigDecimal minimumSettlement) {

    public HousePolicy {
        Objects.requireNonNull(stateCode, "stateCode");
        Objects.requireNonNull(selectionRule, "selectionRule");
        Objects.requireNonNull(overDelivery, "overDelivery");
        if (stateCode.length() != 2) {
            throw new ConfigurationError("state code '" + stateCode + "' should be two letters");
        }
        if (minimumSettlement.signum() < 0) {
            throw new ConfigurationError("minimum settlement cannot be negative");
        }
    }

    /** The conservative defaults: window order, overage stored, cents, no minimum. */
    public static HousePolicy of(String stateCode) {
        return new HousePolicy(stateCode, SelectionRule.WINDOW, OverDelivery.STORE, true, new BigDecimal("0.00"));
    }

    public HousePolicy withSelectionRule(SelectionRule value) {
        return new HousePolicy(stateCode, value, overDelivery, roundSettlementToCents, minimumSettlement);
    }

    public HousePolicy withOverDelivery(OverDelivery value) {
        return new HousePolicy(stateCode, selectionRule, value, roundSettlementToCents, minimumSettlement);
    }

    public HousePolicy withMinimumSettlement(BigDecimal value) {
        return new HousePolicy(stateCode, selectionRule, overDelivery, roundSettlementToCents, value);
    }
}
