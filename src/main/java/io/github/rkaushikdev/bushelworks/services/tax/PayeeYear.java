package io.github.rkaushikdev.bushelworks.services.tax;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One producer's reportable total for one calendar year.
 *
 * @param partyCode the payee
 * @param year the calendar year
 * @param gross the gross of every live statement dated in the year
 * @param reductions the discounts, drying, storage, service and checkoff on them, which are part of
 *     the purchase price
 * @param advancesRecovered the advances recovered on them, which are not income
 * @param settlements how many statements there were
 */
public record PayeeYear(
        String partyCode,
        int year,
        BigDecimal gross,
        BigDecimal reductions,
        BigDecimal advancesRecovered,
        int settlements) {

    public PayeeYear {
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(reductions, "reductions");
        Objects.requireNonNull(advancesRecovered, "advancesRecovered");
    }

    /** Gross less the reductions that are part of the purchase price. */
    public BigDecimal reportable() {
        return Money.amount(gross.subtract(reductions));
    }

    /** Whether the payee reaches the reporting threshold. */
    public boolean isReportable() {
        return reportable().compareTo(Tax.REPORTING_THRESHOLD) >= 0;
    }

    /** How far the payee falls short of the threshold; nothing for one that reaches it. */
    public BigDecimal shortfall() {
        BigDecimal gap = Tax.REPORTING_THRESHOLD.subtract(reportable());
        return gap.signum() > 0 ? gap : Money.ZERO_AMOUNT;
    }
}
