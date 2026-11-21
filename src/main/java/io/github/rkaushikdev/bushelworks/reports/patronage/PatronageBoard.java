package io.github.rkaushikdev.bushelworks.reports.patronage;

import static io.github.rkaushikdev.bushelworks.reports.Columns.clip;
import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import io.github.rkaushikdev.bushelworks.reports.exports.Exports;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Who the house actually does business with, as the board is shown it.
 *
 * <p>At the end of a fiscal year the board wants to know two things that sound like the same thing
 * and are not: who brought the most bushels, and who the house made the most money on. The page
 * ranks on one of them and prints both, and the column that surprises people is the third one,
 * margin per bushel.
 *
 * <p>A co-op also allocates patronage on the members' share of the business. That is the same table
 * with the non members struck out, so non members are starred here rather than printed on a second
 * report that drifts out of step with this one.
 */
public final class PatronageBoard {

    /** The page is this many characters wide. */
    public static final int WIDTH = 80;

    /** Below this many bushels a producer is counted among the others rather than named. */
    public static final BigDecimal FLOOR_BUSHELS = new BigDecimal("1000");

    /** Rank the page on bushels delivered. */
    public static final String BY_BUSHELS = "bushels";

    /** Rank the page on the margin kept. */
    public static final String BY_MARGIN = "margin";

    /** How many producers the page names when nobody says otherwise. */
    public static final int DEFAULT_LIMIT = 25;

    /** What a year rolled up from several houses is stamped with when nobody names it. */
    public static final String EVERY_HOUSE = "ALL";

    /** The spreadsheet's columns. */
    public static final List<String> COLUMNS = List.of(
            "party_code",
            "name",
            "member",
            "loads",
            "bushels",
            "gross",
            "margin",
            "margin_per_bushel",
            "share_percent");

    private static final String HEADINGS =
            "RNK PRODUCER     NAME                        BUSHELS         MARGIN   PER BU";

    private PatronageBoard() {}

    /** The page ranked on bushels, naming the first twenty-five. */
    public static String render(PatronageYear year) {
        return render(year, BY_BUSHELS, DEFAULT_LIMIT);
    }

    /** The page ranked on {@code order}, naming the first twenty-five. */
    public static String render(PatronageYear year, String order) {
        return render(year, order, DEFAULT_LIMIT);
    }

    /**
     * The board page, eighty columns like everything else here.
     *
     * <p>The first {@code limit} producers with at least {@link #FLOOR_BUSHELS} are named, in rank
     * order; everybody else is added up on one line of others. A non member is starred.
     *
     * @param order {@link #BY_BUSHELS} or {@link #BY_MARGIN}; null ranks on bushels
     * @param limit how many to name; a negative limit names all but that many of the producers over
     *     the floor
     * @throws PatronageError for an order that is neither
     */
    public static String render(PatronageYear year, String order, int limit) {
        String ranking = order == null ? BY_BUSHELS : order;
        if (!ranking.equals(BY_BUSHELS) && !ranking.equals(BY_MARGIN)) {
            throw new PatronageError("cannot rank on " + ranking);
        }
        List<PatronageLine> lines = ranking.equals(BY_BUSHELS) ? year.byBushels() : year.byMargin();
        List<String> out = new ArrayList<>();
        out.add("-".repeat(WIDTH));
        out.add(Columns.centred("PATRONAGE BY " + ranking.toUpperCase(Locale.ROOT), WIDTH));
        out.add(Columns.centred(year.locationCode() + "  " + year.opens() + " to " + year.closes(), WIDTH));
        out.add("-".repeat(WIDTH));
        out.add(HEADINGS);
        out.add("-".repeat(WIDTH));
        List<PatronageLine> shown = PatronageYear.head(
                lines.stream()
                        .filter(line -> line.bushels().compareTo(FLOOR_BUSHELS) >= 0)
                        .toList(),
                limit);
        for (int index = 0; index < shown.size(); index++) {
            out.add(row(shown.get(index), index + 1));
        }
        Set<String> named = shown.stream().map(PatronageLine::partyCode).collect(Collectors.toSet());
        List<PatronageLine> rest =
                lines.stream().filter(line -> !named.contains(line.partyCode())).toList();
        if (!rest.isEmpty()) {
            BigDecimal bushels = BigDecimal.ZERO;
            BigDecimal margin = BigDecimal.ZERO;
            for (PatronageLine line : rest) {
                bushels = bushels.add(line.bushels());
                margin = margin.add(line.margin());
            }
            out.add(left("    " + rest.size() + " others", 38)
                    + right(Money.formatAmount(bushels), 13)
                    + right(Money.formatAmount(margin), 14));
        }
        out.add("-".repeat(WIDTH));
        out.add(left("    TOTAL", 38)
                + right(Money.formatAmount(year.bushels()), 13)
                + right(Money.formatAmount(year.margin()), 14));
        out.add("    top ten hold " + Money.formatAmount(year.concentration()) + " percent of bushels");
        out.add("    * not a member");
        return String.join("\n", out);
    }

    /**
     * The same table for the accountant, who wants a spreadsheet: a header of {@link #COLUMNS} and a
     * row per producer, the most bushels first.
     *
     * <p>The cells follow the export rules rather than rules of their own: decimals as text at the
     * places they were kept, no thousand separators, flags as {@code yes} or {@code no}, and a party
     * code written so a spreadsheet does not eat its leading zeroes. A producer coded 0041 that comes
     * out as 41 is a producer nobody can find.
     */
    public static String toCsv(PatronageYear year) {
        BigDecimal total = year.bushels();
        StringBuilder out = new StringBuilder(Csv.row(COLUMNS));
        for (PatronageLine line : year.byBushels()) {
            out.append(Csv.row(
                    Exports.textField(line.partyCode()),
                    Exports.cell(line.name()),
                    Exports.cell(line.isMember()),
                    Exports.cell(line.loads()),
                    Exports.cell(line.bushels()),
                    Exports.cell(line.gross()),
                    Exports.cell(line.margin()),
                    Exports.cell(line.marginPerBushel()),
                    Exports.cell(PatronageYear.share(line.bushels(), total))));
        }
        return out.toString();
    }

    /** Several houses rolled into one table stamped {@link #EVERY_HOUSE}. */
    public static PatronageYear combine(List<PatronageYear> years) {
        return combine(years, EVERY_HOUSE);
    }

    /**
     * Roll several houses into one table for the board meeting.
     *
     * <p>A producer at more than one house becomes one line: the bushels, dollars and loads added up,
     * the name from the first house, and a member only if a member at every house. The combined year
     * runs from the earliest opening to the latest close.
     *
     * @throws PatronageError when there is nothing to combine
     */
    public static PatronageYear combine(List<PatronageYear> years, String locationCode) {
        if (years.isEmpty()) {
            throw new PatronageError("nothing to combine");
        }
        Map<String, PatronageLine> merged = new LinkedHashMap<>();
        for (PatronageYear year : years) {
            for (PatronageLine line : year.lines()) {
                merged.merge(line.partyCode(), line, PatronageBoard::added);
            }
        }
        LocalDate opens = years.stream()
                .map(PatronageYear::opens)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDate closes = years.stream()
                .map(PatronageYear::closes)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        return new PatronageYear(locationCode, opens, closes, List.copyOf(merged.values()));
    }

    private static PatronageLine added(PatronageLine found, PatronageLine more) {
        return found.toBuilder()
                .bushels(found.bushels().add(more.bushels()))
                .gross(Money.amount(found.gross().add(more.gross())))
                .charges(Money.amount(found.charges().add(more.charges())))
                .isMember(found.isMember() && more.isMember())
                .loads(found.loads() + more.loads())
                .build();
    }

    private static String row(PatronageLine line, int rank) {
        return right(String.valueOf(rank), 3)
                + " "
                + left(clip(line.partyCode(), 10), 10)
                + (line.isMember() ? " " : "*")
                + " "
                + left(clip(line.name(), 22), 22)
                + right(Money.formatAmount(line.bushels()), 13)
                + right(Money.formatAmount(line.margin()), 14)
                + right(line.marginPerBushel().toString(), 9);
    }
}
