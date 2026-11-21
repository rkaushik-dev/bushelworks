package io.github.rkaushikdev.bushelworks.reports.shrinkage;

import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where the bushels went.
 *
 * <p>The house takes shrink off every wet load and then loses some more through the dryer and the
 * leg. Those two numbers are never the same, and the difference is either a profit or an invisible
 * loss depending on which way it runs:
 *
 * <pre>
 *     kept = shrink taken off the producers
 *     lost = what the scale says actually went missing
 *     gain = kept less lost
 * </pre>
 */
public final class Shrinkage {

    /** The sheet is this many characters wide. */
    public static final int WIDTH = 80;

    /** A gain beyond this fraction of the bushels handled, either way, is worth explaining. */
    public static final BigDecimal NOTABLE = new BigDecimal("0.002");

    /** The spreadsheet's columns. */
    public static final List<String> COLUMNS = List.of(
            "commodity_code",
            "location_code",
            "opens",
            "closes",
            "wet_bushels_in",
            "dried_bushels",
            "shrink_charged",
            "measured_loss",
            "gain",
            "charged_percent",
            "measured_percent",
            "is_notable");

    private static final Comparator<ShrinkPeriod> BY_CROP = Comparator.comparing(ShrinkPeriod::commodityCode);

    private Shrinkage() {}

    /** The sheet under the default house name. */
    public static String render(List<ShrinkPeriod> periods) {
        return render(periods, Settings.DEFAULT_HOUSE_NAME);
    }

    /**
     * The sheet the grain accountant takes to the manager: a line per period in crop and site order,
     * bushels whole and percentages to the hundredth, a tie going to the even digit, then the periods
     * worth explaining in the order given.
     */
    public static String render(List<ShrinkPeriod> periods, String houseName) {
        List<String> lines = new ArrayList<>();
        lines.add(Columns.centred(houseName, WIDTH));
        lines.add(Columns.centred("SHRINK RECONCILIATION", WIDTH));
        lines.add("=".repeat(WIDTH));
        lines.add(left("CROP", 7)
                + left("SITE", 8)
                + right("WET IN", 14)
                + right("CHARGED", 12)
                + right("MEASURED", 12)
                + right("GAIN", 12)
                + right("CHG %", 8)
                + right("MEA %", 7));
        lines.add("-".repeat(WIDTH));
        List<ShrinkPeriod> inOrder = periods.stream()
                .sorted(BY_CROP.thenComparing(ShrinkPeriod::locationCode))
                .toList();
        for (ShrinkPeriod period : inOrder) {
            lines.add(left(period.commodityCode(), 7)
                    + left(period.locationCode(), 8)
                    + right(rounded(period.wetBushelsIn(), 0), 14)
                    + right(rounded(period.shrinkCharged(), 0), 12)
                    + right(rounded(period.measuredLoss(), 0), 12)
                    + right(rounded(period.gain(), 0), 12)
                    + right(rounded(period.chargedPercent(), 2), 8)
                    + right(rounded(period.measuredPercent(), 2), 7));
        }
        lines.add("-".repeat(WIDTH));
        List<ShrinkPeriod> notable =
                periods.stream().filter(ShrinkPeriod::isNotable).toList();
        lines.add(periods.size() + " lines, " + notable.size() + " worth explaining");
        for (ShrinkPeriod period : notable) {
            lines.add("  " + period.commodityCode() + " at " + period.locationCode() + ": "
                    + rounded(period.gain().abs(), 0) + " bushels " + period.direction());
        }
        return String.join("\n", lines);
    }

    /**
     * The periods as comma separated text under a header of {@link #COLUMNS}, in crop order:
     * quantities at the places they were kept and the notable flag as {@code yes} or {@code no}.
     */
    public static String toCsv(List<ShrinkPeriod> periods) {
        StringBuilder out = new StringBuilder(Csv.row(COLUMNS));
        for (ShrinkPeriod period : periods.stream().sorted(BY_CROP).toList()) {
            out.append(Csv.row(
                    period.commodityCode(),
                    period.locationCode(),
                    period.opens(),
                    period.closes(),
                    period.wetBushelsIn(),
                    period.driedBushels(),
                    period.shrinkCharged(),
                    period.measuredLoss(),
                    period.gain(),
                    period.chargedPercent(),
                    period.measuredPercent(),
                    period.isNotable() ? "yes" : "no"));
        }
        return out.toString();
    }

    /** Every period's gain added up, at bushel places. */
    public static BigDecimal totalGain(List<ShrinkPeriod> periods) {
        BigDecimal total = BigDecimal.ZERO;
        for (ShrinkPeriod period : periods) {
            total = total.add(period.gain());
        }
        return Units.quantize(total, Units.BUSHEL_PLACES);
    }

    /** The quiet ones, in the order given. A house losing shrink does not notice for a year. */
    public static List<ShrinkPeriod> againstTheHouse(List<ShrinkPeriod> periods) {
        return periods.stream().filter(period -> period.gain().signum() < 0).toList();
    }

    /**
     * {@code value} at {@code places}, a tie going to the even digit. A value below zero that rounds
     * to nothing keeps its minus sign, so the sheet still shows which side of zero the line was on.
     */
    private static String rounded(BigDecimal value, int places) {
        BigDecimal result = value.setScale(places, RoundingMode.HALF_EVEN);
        String text = result.toPlainString();
        return result.signum() == 0 && value.signum() < 0 ? "-" + text : text;
    }
}
