package io.github.rkaushikdev.bushelworks.reports.remittance;

import static io.github.rkaushikdev.bushelworks.reports.Columns.clip;
import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The checkoff return the house files with each commission.
 *
 * <p>Quarterly, one return per commission per commodity, listing the bushels the levy was collected
 * on and the money withheld. The commission reconciles the two against its own rate, so the bushels
 * have to be the bushels the levy was actually computed on rather than everything that crossed the
 * scale. Refundable levies are listed separately from non refundable ones because the commission
 * owes the refund and the house does not.
 */
public final class Remittance {

    /** The width of the printed return. */
    public static final int WIDTH = 76;

    private static final int[][] QUARTER_ENDS = {{3, 31}, {6, 30}, {9, 30}, {12, 31}};

    private Remittance() {}

    /** The quarter a day falls in, 1 to 4. */
    public static int quarterOf(LocalDate day) {
        return (day.getMonthValue() - 1) / 3 + 1;
    }

    /**
     * The last day of a quarter.
     *
     * @throws RemittanceError when the quarter is not 1 to 4
     */
    public static LocalDate quarterEnd(int year, int quarter) {
        checkQuarter(quarter);
        int[] end = QUARTER_ENDS[quarter - 1];
        return LocalDate.of(year, end[0], end[1]);
    }

    /**
     * The first day of a quarter.
     *
     * @throws RemittanceError when the quarter is not 1 to 4
     */
    public static LocalDate quarterStart(int year, int quarter) {
        checkQuarter(quarter);
        return LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
    }

    private static void checkQuarter(int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new RemittanceError(quarter + " is not a quarter");
        }
    }

    /** One commission's levy on one commodity: what a return is keyed by. */
    private record Key(String code, String commodityCode) {

        static final Comparator<Key> ORDER = Comparator.comparing(Key::code).thenComparing(Key::commodityCode);
    }

    /**
     * Split a quarter's collections into one return per commission, ordered by levy code and then
     * commodity.
     *
     * @throws RemittanceError when a collection falls outside the quarter
     */
    public static List<CheckoffReturn> group(Iterable<Collected> entries, int year, int quarter) {
        Map<Key, CheckoffReturn> returns = new TreeMap<>(Key.ORDER);
        for (Collected entry : entries) {
            Key key = new Key(entry.line().code(), entry.commodityCode());
            returns.computeIfAbsent(
                            key,
                            ignored -> new CheckoffReturn(
                                    entry.line().authority(), key.code(), key.commodityCode(), year, quarter))
                    .add(entry);
        }
        return List.copyOf(returns.values());
    }

    /** The return under the default house name. */
    public static String render(CheckoffReturn one) {
        return render(one, Settings.DEFAULT_HOUSE_NAME);
    }

    /** The return as the commission wants it on paper, its collections in settlement date order. */
    public static String render(CheckoffReturn one, String houseName) {
        List<String> lines = new ArrayList<>();
        lines.add(Columns.centred(houseName, WIDTH));
        lines.add(Columns.centred("CHECKOFF RETURN " + one.period(), WIDTH));
        lines.add("=".repeat(WIDTH));
        lines.add("Commission   " + one.authority());
        lines.add("Levy         " + one.code());
        lines.add("Commodity    " + one.commodityCode());
        lines.add("Period       " + quarterStart(one.year(), one.quarter()) + " to "
                + quarterEnd(one.year(), one.quarter()));
        lines.add("-".repeat(WIDTH));
        lines.add(left("SETTLEMENT", 16)
                + left("DATE", 12)
                + left("PRODUCER", 18)
                + right("BUSHELS", 14)
                + right("WITHHELD", 14));
        lines.add("-".repeat(WIDTH));
        List<Collected> inOrder = one.entries().stream()
                .sorted(Comparator.comparing(Collected::settledOn).thenComparing(Collected::settlementNumber))
                .toList();
        for (Collected entry : inOrder) {
            lines.add(left(entry.settlementNumber(), 16)
                    + left(entry.settledOn().toString(), 12)
                    + left(clip(entry.partyCode(), 16), 18)
                    + right(entry.bushels().toString(), 14)
                    + right(entry.line().dollars().toString(), 14));
        }
        lines.add("-".repeat(WIDTH));
        lines.add(one.entries().size() + " settlements, " + one.producers().size() + " producers"
                + right(one.bushels().toString(), WIDTH - 40)
                + right(one.withheld().toString(), 14));
        if (one.refundable().signum() != 0) {
            lines.add("of which refundable" + right(one.refundable().toString(), WIDTH - 19));
        }
        return String.join("\n", lines);
    }

    /** Every return in the quarter, one row per settlement, each return's rows by settlement number. */
    public static String toCsv(List<CheckoffReturn> returns) {
        StringBuilder out = new StringBuilder();
        out.append(Csv.row(
                "authority",
                "levy",
                "commodity",
                "period",
                "settlement",
                "settled_on",
                "party_code",
                "bushels",
                "withheld",
                "refundable"));
        for (CheckoffReturn one : returns) {
            List<Collected> byNumber = one.entries().stream()
                    .sorted(Comparator.comparing(Collected::settlementNumber))
                    .toList();
            for (Collected entry : byNumber) {
                out.append(Csv.row(
                        one.authority(),
                        one.code(),
                        one.commodityCode(),
                        one.period(),
                        entry.settlementNumber(),
                        entry.settledOn().toString(),
                        entry.partyCode(),
                        entry.bushels(),
                        entry.line().dollars(),
                        entry.line().refundable() ? "yes" : "no"));
            }
        }
        return out.toString();
    }

    /** What the house owes each commission this quarter, by levy code in the order first met. */
    public static Map<String, BigDecimal> totals(List<CheckoffReturn> returns) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (CheckoffReturn one : returns) {
            out.merge(one.code(), Money.amount(one.withheld()), (sum, more) -> Money.amount(sum.add(more)));
        }
        return Ordered.copyOf(out);
    }
}
