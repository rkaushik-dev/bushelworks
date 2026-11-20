package io.github.rkaushikdev.bushelworks.domain.settlement;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Collects lines and hands back a statement in canonical order.
 *
 * <p>The order is enforced here rather than by the caller remembering it: lines sort by their
 * kind's position and then by code, and the statement checks that it balances before it is handed
 * back. A line of zero dollars is dropped rather than printed.
 */
public final class SettlementBuilder {

    private final String number;
    private final LocalDate settledOn;
    private final String partyId;
    private final String locationCode;
    private final List<SettlementLine> lines = new ArrayList<>();
    private final List<String> tickets = new ArrayList<>();
    private final List<String> contracts = new ArrayList<>();

    public SettlementBuilder(String number, LocalDate settledOn, String partyId, String locationCode) {
        this.number = number;
        this.settledOn = settledOn;
        this.partyId = partyId;
        this.locationCode = locationCode;
    }

    public String number() {
        return number;
    }

    public LocalDate settledOn() {
        return settledOn;
    }

    public String partyId() {
        return partyId;
    }

    public String locationCode() {
        return locationCode;
    }

    public SettlementBuilder add(SettlementLine line) {
        if (line.dollars().signum() != 0) {
            lines.add(line);
        }
        return this;
    }

    /** The value of priced bushels, extended to the cent once. */
    public SettlementBuilder addGross(String code, String description, BigDecimal bushels, BigDecimal unitPrice) {
        return add(new SettlementLine(
                LineKind.GROSS,
                code,
                description,
                Money.extend(bushels, unitPrice),
                Optional.of(bushels),
                Optional.of(unitPrice)));
    }

    public SettlementBuilder addCharge(LineKind kind, String code, String description, BigDecimal dollars) {
        return addCharge(kind, code, description, dollars, Optional.empty(), Optional.empty());
    }

    public SettlementBuilder addCharge(
            LineKind kind,
            String code,
            String description,
            BigDecimal dollars,
            Optional<BigDecimal> quantity,
            Optional<BigDecimal> rate) {
        return add(new SettlementLine(kind, code, description, Money.amount(dollars), quantity, rate));
    }

    /** Reference a ticket the statement settles. A second reference to the same one is ignored. */
    public SettlementBuilder referenceTicket(String ticketNumber) {
        if (!tickets.contains(ticketNumber)) {
            tickets.add(ticketNumber);
        }
        return this;
    }

    /** Reference a contract the statement settles against. A repeat is ignored. */
    public SettlementBuilder referenceContract(String contractNumber) {
        if (!contracts.contains(contractNumber)) {
            contracts.add(contractNumber);
        }
        return this;
    }

    public BigDecimal runningGross() {
        return runningTotal(LineKind.GROSS);
    }

    public BigDecimal runningDiscounts() {
        return runningTotal(LineKind.DISCOUNT);
    }

    private BigDecimal runningTotal(LineKind kind) {
        return Money.total(lines.stream()
                .filter(line -> line.kind() == kind)
                .map(SettlementLine::dollars)
                .toList());
    }

    /** The statement, in canonical order, checked to balance. */
    public Settlement build() {
        List<SettlementLine> ordered = lines.stream()
                .sorted(Comparator.comparingInt(
                                (SettlementLine line) -> line.kind().position())
                        .thenComparing(SettlementLine::code))
                .toList();
        Settlement settlement = new Settlement(number, settledOn, partyId, locationCode, ordered, tickets, contracts);
        settlement.checkBalances();
        return settlement;
    }
}
