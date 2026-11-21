package io.github.rkaushikdev.bushelworks.reports.remittance;

import io.github.rkaushikdev.bushelworks.domain.assessments.WithheldLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One levy collected on one settlement, as it goes onto the return.
 *
 * @param settlementNumber the statement the levy was withheld on
 * @param settledOn the day it was settled
 * @param partyCode the producer
 * @param commodityCode the commodity levied
 * @param bushels the bushels the levy was actually computed on
 * @param line the levy as withheld
 */
public record Collected(
        String settlementNumber,
        LocalDate settledOn,
        String partyCode,
        String commodityCode,
        BigDecimal bushels,
        WithheldLine line) {

    public Collected {
        Objects.requireNonNull(settlementNumber, "settlementNumber");
        Objects.requireNonNull(settledOn, "settledOn");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(bushels, "bushels");
        Objects.requireNonNull(line, "line");
    }
}
