package io.github.rkaushikdev.bushelworks.reports.position;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.domain.position.PositionLine;
import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import io.github.rkaushikdev.bushelworks.domain.position.Positions;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The daily position board, printed and as a spreadsheet.
 *
 * <p>Two renderings of the same numbers. The printed one goes on the wall by the manager's desk and
 * the CSV goes to whoever asks for it, which is usually the lender. Neither computes anything: if a
 * total is wrong it is wrong in {@link PositionLine} and this shows it faithfully.
 */
public final class PositionBoard {

    /** How wide the printed board is, in characters. */
    public static final int WIDTH = 98;

    /** The house named on the board when nobody names another. */
    public static final String HOUSE_NAME = "BUSHELWORKS ELEVATOR";

    private static final BigDecimal CONTRACT_SIZE = new BigDecimal("5000");

    private static final Comparator<PositionLine> BOARD_ORDER =
            Comparator.comparing(PositionLine::commodityCode).thenComparing(PositionLine::locationCode);

    /** One column of the board: its heading, how a line fills it, and how wide it is. */
    private record Column(String title, Function<PositionLine, Object> value, int width) {}

    private static final List<Column> COLUMNS = List.of(
            new Column("COMMODITY", PositionLine::commodityCode, 12),
            new Column("SITE", PositionLine::locationCode, 8),
            new Column("ON HAND", PositionLine::onHand, 14),
            new Column("STORAGE", PositionLine::storageObligation, 14),
            new Column("DP", PositionLine::deferredPrice, 12),
            new Column("BOUGHT", PositionLine::purchasedForward, 12),
            new Column("SOLD", PositionLine::soldForward, 12));

    private PositionBoard() {}

    /**
     * Bushels as a report prints them: whole, a half going to the even one, with thousands separated
     * by commas and a short in brackets. Nobody hedges a tenth of a bushel. A quantity below zero that
     * rounds to nothing prints as {@code -0}.
     */
    public static String bushels(BigDecimal value) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_EVEN);
        if (rounded.signum() < 0) {
            return "(" + grouped(rounded.negate()) + ")";
        }
        if (rounded.signum() == 0 && value.signum() < 0) {
            return "-0";
        }
        return grouped(rounded);
    }

    private static String grouped(BigDecimal whole) {
        return String.format(Locale.ROOT, "%,d", whole.toBigIntegerExact());
    }

    /** The board's column headings, the two names left aligned and the quantities right aligned. */
    public static String header() {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < COLUMNS.size(); index++) {
            Column column = COLUMNS.get(index);
            out.append(
                    index > 1
                            ? Columns.right(column.title(), column.width())
                            : Columns.left(column.title(), column.width()));
        }
        return out.append(Columns.right("NET LONG", 14)).toString();
    }

    /** One line of the board. */
    public static String row(PositionLine line) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < COLUMNS.size(); index++) {
            Column column = COLUMNS.get(index);
            Object value = column.value().apply(line);
            out.append(
                    index > 1
                            ? Columns.right(bushels((BigDecimal) value), column.width())
                            : Columns.left(value.toString(), column.width()));
        }
        return out.append(Columns.right(bushels(line.netLong()), 14)).toString();
    }

    /** The board as it goes on the wall, for the house. */
    public static String render(PositionReport report) {
        return render(report, HOUSE_NAME);
    }

    /**
     * The board as it goes on the wall: a line per commodity and site in that order, what the house
     * owns in total, and every short called out at the bottom.
     */
    public static String render(PositionReport report, String houseName) {
        List<String> lines = new ArrayList<>();
        lines.add(Columns.centred(houseName, WIDTH));
        lines.add(Columns.centred("POSITION AS OF " + report.asOf(), WIDTH));
        lines.add("=".repeat(WIDTH));
        lines.add(header());
        lines.add("-".repeat(WIDTH));
        report.lines().stream().sorted(BOARD_ORDER).map(PositionBoard::row).forEach(lines::add);
        lines.add("-".repeat(WIDTH));
        lines.add(Columns.left("TOTAL OWNED", 20) + Columns.right(bushels(report.totalOwned()), 20));

        List<PositionLine> shorts = report.shorts();
        if (!shorts.isEmpty()) {
            lines.add("");
            lines.add("SHORT:");
            for (PositionLine line : shorts) {
                lines.add("  " + line.commodityCode() + " at " + line.locationCode() + " is short "
                        + bushels(line.netLong().abs()) + " bushels");
            }
        }
        return String.join("\n", lines);
    }

    /** What the desk would have to sell to be flat, in whole five thousand bushel contracts. */
    public static String hedgeSheet(PositionReport report) {
        return hedgeSheet(report, CONTRACT_SIZE);
    }

    /**
     * What the desk would have to sell to be flat, in whole contracts of {@code contractSize}
     * bushels, rounded toward zero, and the bushels left over.
     */
    public static String hedgeSheet(PositionReport report, BigDecimal contractSize) {
        List<String> lines = new ArrayList<>();
        lines.add(Columns.centred("HEDGE SHEET " + report.asOf(), WIDTH));
        lines.add("=".repeat(WIDTH));
        lines.add(Columns.left("COMMODITY", 12)
                + Columns.right("NET LONG", 16)
                + Columns.right("CONTRACTS", 14)
                + Columns.right("RESIDUAL", 16));
        lines.add("-".repeat(WIDTH));
        for (String code : report.commodities()) {
            PositionLine line = report.byCommodity(code);
            int lots = Positions.hedgeRequirement(line, contractSize);
            BigDecimal residual =
                    line.netLong().subtract(BigDecimal.valueOf(lots).multiply(contractSize));
            lines.add(Columns.left(code, 12)
                    + Columns.right(bushels(line.netLong()), 16)
                    + Columns.right(Integer.toString(lots), 14)
                    + Columns.right(bushels(residual), 16));
        }
        return String.join("\n", lines);
    }

    /** The same rows for a spreadsheet, quantities unrounded, with what is owned and the net long. */
    public static String toCsv(PositionReport report) {
        StringBuilder out = new StringBuilder(Csv.row(
                "as_of",
                "commodity_code",
                "location_code",
                "on_hand",
                "storage_obligation",
                "deferred_price",
                "purchased_forward",
                "sold_forward",
                "in_transit",
                "owned",
                "net_long"));
        report.lines().stream()
                .sorted(BOARD_ORDER)
                .forEach(line -> out.append(Csv.row(
                        line.asOf(),
                        line.commodityCode(),
                        line.locationCode(),
                        line.onHand(),
                        line.storageObligation(),
                        line.deferredPrice(),
                        line.purchasedForward(),
                        line.soldForward(),
                        line.inTransit(),
                        line.owned(),
                        line.netLong())));
        return out.toString();
    }

    /**
     * The day's move in net long, by commodity, in code order. A commodity on only one of the two days
     * moves by its whole position.
     *
     * <p>What the manager actually looks at. A position that is right and a position that moved four
     * hundred thousand bushels overnight are different kinds of right.
     */
    public static Map<String, BigDecimal> compare(List<PositionLine> yesterday, List<PositionLine> today) {
        Map<String, BigDecimal> before = totals(yesterday);
        Map<String, BigDecimal> after = totals(today);
        TreeSet<String> codes = new TreeSet<>(before.keySet());
        codes.addAll(after.keySet());
        Map<String, BigDecimal> moves = new LinkedHashMap<>();
        for (String code : codes) {
            moves.put(
                    code,
                    after.getOrDefault(code, BigDecimal.ZERO).subtract(before.getOrDefault(code, BigDecimal.ZERO)));
        }
        return moves;
    }

    private static Map<String, BigDecimal> totals(List<PositionLine> lines) {
        Map<String, BigDecimal> out = new TreeMap<>();
        for (PositionLine line : lines) {
            out.merge(line.commodityCode(), line.netLong(), BigDecimal::add);
        }
        return out;
    }
}
