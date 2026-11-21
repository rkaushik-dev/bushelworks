package io.github.rkaushikdev.bushelworks.domain.insurance;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One unit's production for one crop year.
 *
 * <p>The house does not sell insurance and does not settle claims. What it does is produce the
 * numbers the adjuster asks for, and get them wrong at its peril: a production report that
 * disagrees with the settlement statements is the thing that turns a claim into an investigation.
 */
public final class Production {

    private static final BigDecimal YIELD_PLACES = new BigDecimal("0.01");
    private static final BigDecimal PERCENT_PLACES = new BigDecimal("0.1");

    private final Unit unit;
    private final List<Delivery> deliveries = new ArrayList<>();
    private final BigDecimal farmStored;

    public Production(Unit unit) {
        this(unit, BigDecimal.ZERO);
    }

    public Production(Unit unit, BigDecimal farmStored) {
        this.unit = unit;
        this.farmStored = farmStored;
    }

    public Unit unit() {
        return unit;
    }

    public List<Delivery> deliveries() {
        return List.copyOf(deliveries);
    }

    /** What was kept on the farm and never came to town. */
    public BigDecimal farmStored() {
        return farmStored;
    }

    public void add(Delivery delivery) {
        if (!unit.covers(delivery.farmCode())) {
            throw new ConflictError(unit.unitNumber() + " does not cover " + delivery.farmCode());
        }
        if (deliveries.stream().anyMatch(item -> item.ticketNumber().equals(delivery.ticketNumber()))) {
            throw new ConflictError(delivery.ticketNumber() + " is already on " + unit.unitNumber());
        }
        deliveries.add(delivery);
    }

    public BigDecimal delivered() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Delivery item : deliveries) {
            sum = sum.add(item.bushels());
        }
        return Units.quantize(sum, Units.BUSHEL_PLACES);
    }

    /** Everything produced, including what never came to town. */
    public BigDecimal total() {
        return Units.quantize(delivered().add(farmStored), Units.BUSHEL_PLACES);
    }

    public BigDecimal yieldPerAcre() {
        return Units.quantize(Decimals.divide(total(), unit.acres()), YIELD_PLACES);
    }

    /** Bushels below the guarantee. Zero is a good year, not an error. */
    public BigDecimal shortfall() {
        BigDecimal gap = unit.guaranteedBushels().subtract(total());
        return gap.signum() > 0 ? Units.quantize(gap, Units.BUSHEL_PLACES) : new BigDecimal("0.0000");
    }

    public boolean hasAClaim() {
        return shortfall().signum() > 0;
    }

    public BigDecimal percentOfGuarantee() {
        BigDecimal guarantee = unit.guaranteedBushels();
        if (guarantee.signum() <= 0) {
            return new BigDecimal("0.0");
        }
        return Units.quantize(Decimals.divide(total(), guarantee).multiply(Decimals.HUNDRED), PERCENT_PLACES);
    }
}
