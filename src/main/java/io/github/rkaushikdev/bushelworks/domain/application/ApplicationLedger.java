package io.github.rkaushikdev.bushelworks.domain.application;

import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** How much has landed on each contract so far. */
public final class ApplicationLedger {

    private static final BigDecimal NONE = new BigDecimal("0.0000");

    private final Map<String, BigDecimal> applied = new HashMap<>();

    public BigDecimal applied(String contractNumber) {
        return applied.getOrDefault(contractNumber, NONE);
    }

    /** Count an application against its contract. An overage counts against nothing. */
    public void record(Application application) {
        application
                .contractNumber()
                .ifPresent(number -> applied.put(
                        number, Units.quantize(applied(number).add(application.bushels()), Units.BUSHEL_PLACES)));
    }

    /** Bushels the contract can still take, up to its tolerance ceiling. */
    public BigDecimal remaining(PurchaseContract contract) {
        BigDecimal room = contract.maximumBushels().subtract(applied(contract.number()));
        return room.signum() > 0 ? room : NONE;
    }

    /**
     * Filled means inside tolerance, not exactly on the number.
     *
     * <p>A twenty thousand bushel contract with a two percent tolerance is filled at 19,600. The
     * house does not chase the last four hundred bushels and the producer is not in default for
     * them.
     */
    public boolean isFilled(PurchaseContract contract) {
        return applied(contract.number()).compareTo(contract.minimumBushels()) >= 0;
    }

    public BigDecimal shortfall(PurchaseContract contract) {
        BigDecimal gap = contract.minimumBushels().subtract(applied(contract.number()));
        return gap.signum() > 0 ? gap : NONE;
    }

    /** The contracts anything has landed on, sorted by number. */
    public List<String> contractsTouched() {
        return applied.keySet().stream().sorted().toList();
    }
}
