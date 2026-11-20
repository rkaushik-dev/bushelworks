package io.github.rkaushikdev.bushelworks.domain.settlement;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A finished statement for one party: an ordered list of lines and a net.
 *
 * @param number the statement number
 * @param settledOn the day it was settled
 * @param partyId who it pays
 * @param locationCode where
 * @param lines the lines, in statement order
 * @param ticketNumbers the tickets it settles, in the order they were referenced
 * @param contractNumbers the contracts it settles against
 */
public record Settlement(
        String number,
        LocalDate settledOn,
        String partyId,
        String locationCode,
        List<SettlementLine> lines,
        List<String> ticketNumbers,
        List<String> contractNumbers) {

    public Settlement {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(settledOn, "settledOn");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(locationCode, "locationCode");
        lines = List.copyOf(lines);
        ticketNumbers = List.copyOf(ticketNumbers);
        contractNumbers = List.copyOf(contractNumbers);
    }

    /** A statement that references no tickets and no contracts. */
    public Settlement(
            String number, LocalDate settledOn, String partyId, String locationCode, List<SettlementLine> lines) {
        this(number, settledOn, partyId, locationCode, lines, List.of(), List.of());
    }

    public BigDecimal gross() {
        return subtotal(LineKind.GROSS);
    }

    /** Everything that reduces the net, as a positive amount. */
    public BigDecimal deductions() {
        return Money.total(lines.stream()
                .filter(line -> line.kind().reducing())
                .map(SettlementLine::dollars)
                .toList());
    }

    public BigDecimal net() {
        return Money.total(lines.stream().map(SettlementLine::signed).toList());
    }

    public List<SettlementLine> ofKind(LineKind kind) {
        return lines.stream().filter(line -> line.kind() == kind).toList();
    }

    public BigDecimal subtotal(LineKind kind) {
        return Money.total(ofKind(kind).stream().map(SettlementLine::dollars).toList());
    }

    /** Gross less quality discounts. What a percentage assessment sits on. */
    public BigDecimal valueAfterDiscounts() {
        return Money.amount(gross().subtract(subtotal(LineKind.DISCOUNT)));
    }

    /** Refuse unless the net is the gross less everything, to the cent. */
    public void checkBalances() {
        BigDecimal expected = Money.amount(gross().subtract(deductions()));
        if (expected.compareTo(net()) != 0) {
            throw new SettlementError(number + ": net " + net() + " does not equal gross less deductions " + expected);
        }
    }
}
