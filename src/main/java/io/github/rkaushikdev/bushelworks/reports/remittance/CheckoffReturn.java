package io.github.rkaushikdev.bushelworks.reports.remittance;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One commission, one commodity, one quarter: the return the house files with its levy money.
 *
 * <p>The commission reconciles the bushels against the money at its own rate, so only collections
 * of this levy, on this commodity, settled inside the quarter, go on it.
 */
public final class CheckoffReturn {

    private final String authority;
    private final String code;
    private final String commodityCode;
    private final int year;
    private final int quarter;
    private final List<Collected> entries = new ArrayList<>();

    public CheckoffReturn(String authority, String code, String commodityCode, int year, int quarter) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.code = Objects.requireNonNull(code, "code");
        this.commodityCode = Objects.requireNonNull(commodityCode, "commodityCode");
        this.year = year;
        this.quarter = quarter;
    }

    public String authority() {
        return authority;
    }

    /** The levy's code. */
    public String code() {
        return code;
    }

    public String commodityCode() {
        return commodityCode;
    }

    public int year() {
        return year;
    }

    public int quarter() {
        return quarter;
    }

    /** The collections on the return, in the order they were added. */
    public List<Collected> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Put one collection on the return.
     *
     * @throws RemittanceError when it is another levy, another commodity, or outside the quarter
     */
    public void add(Collected entry) {
        if (!entry.line().code().equals(code)) {
            throw new RemittanceError(entry.settlementNumber() + ": "
                    + entry.line().code() + " does not belong on a " + code + " return");
        }
        if (!entry.commodityCode().equals(commodityCode)) {
            throw new RemittanceError(entry.settlementNumber() + ": " + entry.commodityCode() + " does not belong on a "
                    + commodityCode + " return");
        }
        if (!covers(entry.settledOn())) {
            throw new RemittanceError(
                    entry.settlementNumber() + ": " + entry.settledOn() + " is outside " + year + " Q" + quarter);
        }
        entries.add(entry);
    }

    /** Whether a day falls inside the quarter, both ends included. */
    public boolean covers(LocalDate day) {
        return !day.isBefore(Remittance.quarterStart(year, quarter))
                && !day.isAfter(Remittance.quarterEnd(year, quarter));
    }

    /** The bushels the levy was collected on, exactly as each entry gave them. */
    public BigDecimal bushels() {
        BigDecimal sum = BigDecimal.ZERO;
        for (Collected entry : entries) {
            sum = sum.add(entry.bushels());
        }
        return sum;
    }

    public BigDecimal withheld() {
        return Money.total(entries.stream().map(entry -> entry.line().dollars()).toList());
    }

    /** The part of what was withheld the commission refunds on request. */
    public BigDecimal refundable() {
        return Money.total(entries.stream()
                .filter(entry -> entry.line().refundable())
                .map(entry -> entry.line().dollars())
                .toList());
    }

    /** The producers on the return, once each, in code order. */
    public List<String> producers() {
        return entries.stream().map(Collected::partyCode).distinct().sorted().toList();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** {@code 2024 Q4}. */
    public String period() {
        return year + " Q" + quarter;
    }
}
