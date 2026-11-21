package io.github.rkaushikdev.bushelworks.reports.yearend;

import static io.github.rkaushikdev.bushelworks.reports.Columns.clip;
import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The producer's year in one page.
 *
 * <p>Every January the office prints one of these for each producer and posts it with the 1099. It
 * is the only document that shows a whole crop year at once, and it is the one producers actually
 * read, so the arithmetic on it has to agree with the statements they already have in a drawer.
 */
public final class YearEnd {

    public static final int WIDTH = 80;

    /**
     * The month a producer was paid most in.
     *
     * @param month the calendar month, January being 1
     * @param paid the net paid that month
     */
    public record MonthPaid(int month, BigDecimal paid) {}

    /**
     * The first and last days of a crop year.
     *
     * @param opens the first of September of the year before
     * @param closes the last of August
     */
    public record CropYear(LocalDate opens, LocalDate closes) {}

    private YearEnd() {}

    /** Gather one producer's year out of a run of statements, leaving out everyone else's. */
    public static YearEndSummary build(String partyCode, int year, List<Settlement> settlements) {
        YearEndSummary summary = new YearEndSummary(partyCode, year);
        for (Settlement statement : settlements) {
            if (statement.partyId().equals(partyCode) && statement.settledOn().getYear() == year) {
                summary.add(statement);
            }
        }
        return summary;
    }

    /** The page under the default house name. */
    public static String render(YearEndSummary summary) {
        return render(summary, Settings.DEFAULT_HOUSE_NAME);
    }

    /** The page that goes in the envelope; every kind but the gross prints as a reduction. */
    public static String render(YearEndSummary summary, String houseName) {
        List<String> lines = new ArrayList<>();
        lines.add(Columns.centred(houseName, WIDTH));
        lines.add(Columns.centred("YEAR END SUMMARY " + summary.year(), WIDTH));
        lines.add("=".repeat(WIDTH));
        lines.add("Producer     " + summary.partyCode());
        lines.add("Settlements  " + summary.count());
        lines.add("Tickets      " + summary.tickets().size());
        lines.add("-".repeat(WIDTH));
        for (Map.Entry<LineKind, BigDecimal> kind : summary.byKind().entrySet()) {
            String label = kind.getKey().value().replace('_', ' ').toUpperCase(Locale.ROOT);
            BigDecimal signed = kind.getKey() == LineKind.GROSS
                    ? kind.getValue()
                    : kind.getValue().negate();
            lines.add("  " + left(label, 40) + right(Money.formatAmount(signed), 20));
        }
        lines.add("-".repeat(WIDTH));
        lines.add("  " + left("NET PAID", 40) + right(Money.formatAmount(summary.net()), 20));
        if (summary.settlements().isEmpty()) {
            lines.add("");
            lines.add("Nothing settled in this year.");
        }
        return String.join("\n", lines);
    }

    /** The list of statements the summary is built from, in date order, for the second page. */
    public static String statementIndex(YearEndSummary summary) {
        List<String> lines = new ArrayList<>();
        lines.add(left("STATEMENT", 16) + left("DATE", 12) + left("TICKETS", 28) + right("NET", 14));
        lines.add("-".repeat(72));
        List<Settlement> inDateOrder = summary.settlements().stream()
                .sorted(Comparator.comparing(Settlement::settledOn))
                .toList();
        for (Settlement statement : inDateOrder) {
            String tickets = String.join(", ", statement.ticketNumbers());
            lines.add(left(statement.number(), 16)
                    + left(statement.settledOn().toString(), 12)
                    + left(clip(tickets, 26), 28)
                    + right(Money.formatAmount(statement.net()), 14));
        }
        lines.add("-".repeat(72));
        lines.add(summary.count() + " statements" + right(Money.formatAmount(summary.net()), 60));
        return String.join("\n", lines);
    }

    /**
     * The month the producer was paid most in, which he will ask about. Of months paid the same, the
     * one whose first statement was added first; nothing in a year with no statements.
     */
    public static Optional<MonthPaid> busiestMonth(YearEndSummary summary) {
        Map<Integer, BigDecimal> byMonth = new LinkedHashMap<>();
        for (Settlement statement : summary.settlements()) {
            byMonth.merge(
                    statement.settledOn().getMonthValue(),
                    Money.amount(statement.net()),
                    (sum, more) -> Money.amount(sum.add(more)));
        }
        Optional<MonthPaid> busiest = Optional.empty();
        for (Map.Entry<Integer, BigDecimal> month : byMonth.entrySet()) {
            if (busiest.isEmpty() || month.getValue().compareTo(busiest.get().paid()) > 0) {
                busiest = Optional.of(new MonthPaid(month.getKey(), month.getValue()));
            }
        }
        return busiest;
    }

    /** A crop year runs from the first of September to the last of August. */
    public static CropYear cropYearSpan(int year) {
        return new CropYear(LocalDate.of(year - 1, 9, 1), LocalDate.of(year, 8, 31));
    }
}
