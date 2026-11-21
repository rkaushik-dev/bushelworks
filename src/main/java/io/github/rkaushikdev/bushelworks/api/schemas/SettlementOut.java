package io.github.rkaushikdev.bushelworks.api.schemas;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A settlement statement, read back.
 *
 * @param number the statement number
 * @param settledOn the day it was settled
 * @param partyCode the producer it pays
 * @param locationCode the site
 * @param gross the grain's value before anything came off
 * @param deductions everything that came off
 * @param net what the cheque is for
 * @param ticketNumbers the tickets it settled
 * @param contractNumbers the contracts it priced against
 * @param lines the statement's lines
 */
public record SettlementOut(
        String number,
        LocalDate settledOn,
        String partyCode,
        String locationCode,
        BigDecimal gross,
        BigDecimal deductions,
        BigDecimal net,
        List<String> ticketNumbers,
        List<String> contractNumbers,
        List<SettlementLineOut> lines) {

    public SettlementOut {
        Objects.requireNonNull(number, "number");
        ticketNumbers = List.copyOf(ticketNumbers);
        contractNumbers = List.copyOf(contractNumbers);
        lines = List.copyOf(lines);
    }
}
