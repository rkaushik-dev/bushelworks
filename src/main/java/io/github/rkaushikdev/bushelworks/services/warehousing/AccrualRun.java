package io.github.rkaushikdev.bushelworks.services.warehousing;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The result of one pass over the open lots.
 *
 * @param through the day the pass billed to
 * @param lines what each lot accrued, in the order the lots were stored
 */
public record AccrualRun(LocalDate through, List<AccrualLine> lines) {

    public AccrualRun {
        Objects.requireNonNull(through, "through");
        lines = List.copyOf(lines);
    }

    /** Everything the pass charged, in dollars and cents. */
    public BigDecimal total() {
        return Money.amount(lines.stream().map(AccrualLine::dollars).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** The lines for one producer's lots. */
    public List<AccrualLine> forParty(String partyCode) {
        return lines.stream().filter(line -> line.partyCode().equals(partyCode)).toList();
    }
}
