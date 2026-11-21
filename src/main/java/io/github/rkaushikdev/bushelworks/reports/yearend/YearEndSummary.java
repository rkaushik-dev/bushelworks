package io.github.rkaushikdev.bushelworks.reports.yearend;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One producer, one crop year: the statements settled to him that year, added up as written.
 *
 * <p>Nothing here recomputes anything. A year end summary that disagrees with the statements it
 * summarises is worse than no summary at all, so it reads the settlements as written and {@link
 * #checkBalances} proves the two agree.
 */
public final class YearEndSummary {

    private final String partyCode;
    private final int year;
    private final List<Settlement> settlements;

    /** An empty summary for one producer's year. */
    public YearEndSummary(String partyCode, int year) {
        this(partyCode, year, List.of());
    }

    /** A summary of statements already gathered, taken as they are without checking them. */
    public YearEndSummary(String partyCode, int year, List<Settlement> settlements) {
        this.partyCode = Objects.requireNonNull(partyCode, "partyCode");
        this.year = year;
        this.settlements = new ArrayList<>(settlements);
    }

    public String partyCode() {
        return partyCode;
    }

    public int year() {
        return year;
    }

    /** The statements summarised, in the order they were added. */
    public List<Settlement> settlements() {
        return Collections.unmodifiableList(settlements);
    }

    /**
     * Add one more statement.
     *
     * @throws YearEndError when it belongs to another producer or was settled in another year
     */
    public void add(Settlement statement) {
        if (!statement.partyId().equals(partyCode)) {
            throw new YearEndError(statement.number() + " belongs to " + statement.partyId() + ", not " + partyCode);
        }
        if (statement.settledOn().getYear() != year) {
            throw new YearEndError(statement.number() + " was settled in "
                    + statement.settledOn().getYear() + ", not " + year);
        }
        settlements.add(statement);
    }

    public int count() {
        return settlements.size();
    }

    public BigDecimal gross() {
        return Money.total(settlements.stream().map(Settlement::gross).toList());
    }

    public BigDecimal net() {
        return Money.total(settlements.stream().map(Settlement::net).toList());
    }

    /** Every ticket the year's statements settle, once each, in the order they first appear. */
    public List<String> tickets() {
        Set<String> seen = new LinkedHashSet<>();
        for (Settlement statement : settlements) {
            seen.addAll(statement.ticketNumbers());
        }
        return List.copyOf(seen);
    }

    /** Every charge kind across the year, as positive amounts, in statement order. */
    public Map<LineKind, BigDecimal> byKind() {
        Map<LineKind, BigDecimal> totals = new EnumMap<>(LineKind.class);
        for (Settlement statement : settlements) {
            for (SettlementLine line : statement.lines()) {
                totals.merge(line.kind(), Money.amount(line.dollars()), (sum, more) -> Money.amount(sum.add(more)));
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(totals));
    }

    /**
     * The summary has to add back to the statements it summarises: the gross less every other kind
     * has to come to the net the statements paid.
     *
     * @throws YearEndError giving both nets when they differ
     */
    public void checkBalances() {
        Map<LineKind, BigDecimal> kinds = byKind();
        BigDecimal gross = kinds.getOrDefault(LineKind.GROSS, Money.ZERO_AMOUNT);
        BigDecimal reductions = BigDecimal.ZERO;
        for (Map.Entry<LineKind, BigDecimal> kind : kinds.entrySet()) {
            if (kind.getKey() != LineKind.GROSS) {
                reductions = reductions.add(kind.getValue());
            }
        }
        BigDecimal summarised = Money.amount(gross.subtract(Money.amount(reductions)));
        if (summarised.compareTo(net()) != 0) {
            throw new YearEndError(partyCode + " " + year + ": the summary nets to " + summarised
                    + " and the statements net to " + net());
        }
    }
}
