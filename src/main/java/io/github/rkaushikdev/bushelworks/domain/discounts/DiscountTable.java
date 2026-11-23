package io.github.rkaushikdev.bushelworks.domain.discounts;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A discount board written the way it hangs by the scale window: a line a band.
 *
 * <pre>
 *     commodity  factor       start  end   rate    per   mode     label
 *     CORN       test_weight  54.0   52.0  0.0100  0.5   step     light test weight
 *     CORN       test_weight  52.0   -     0.0200  0.5   step     light test weight
 * </pre>
 *
 * <p>Consecutive lines for the same commodity and factor are the bands of one schedule, in the
 * order they run toward the bad end, and a dash in the end column is a band that runs on. The
 * label is everything after the mode, and every band of a schedule has to carry the same one,
 * because the statement prints one label per schedule and two would mean somebody edited half a
 * line. Blank lines and lines starting with {@code #} are passed over, and so is the heading.
 */
public final class DiscountTable {

    private static final String HEADING = "commodity";
    private static final String RUNS_ON = "-";
    private static final int COLUMNS = 8;

    private DiscountTable() {}

    /**
     * The schedules a board describes, in the order it lists them.
     *
     * @throws ScheduleError naming the line, when a line cannot be read or a schedule's bands
     *     carry different labels
     */
    public static List<DiscountSchedule> parse(String board) {
        List<DiscountSchedule> schedules = new ArrayList<>();
        List<DiscountBand> bands = new ArrayList<>();
        String commodity = null;
        Factor factor = null;
        String label = null;
        for (String raw : board.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(HEADING + " ")) {
                continue;
            }
            String[] cells = line.split("\\s+", COLUMNS);
            if (cells.length < COLUMNS - 1) {
                throw new ScheduleError("a discount board line needs " + (COLUMNS - 1) + " columns: " + line);
            }
            String code = cells[0];
            Factor read = Factor.of(cells[1]);
            String named = cells.length == COLUMNS ? cells[7] : "";
            if (!code.equals(commodity) || read != factor) {
                if (!bands.isEmpty()) {
                    schedules.add(new DiscountSchedule(commodity, factor, bands, label));
                }
                bands = new ArrayList<>();
                commodity = code;
                factor = read;
                label = named;
            } else if (!named.equals(label)) {
                throw new ScheduleError(
                        code + "/" + read.value() + ": one band says '" + label + "' and the next '" + named + "'");
            }
            bands.add(band(cells));
        }
        if (!bands.isEmpty()) {
            schedules.add(new DiscountSchedule(commodity, factor, bands, label));
        }
        return List.copyOf(schedules);
    }

    private static DiscountBand band(String[] cells) {
        Optional<BigDecimal> end = cells[3].equals(RUNS_ON) ? Optional.empty() : Optional.of(number(cells, 3));
        return new DiscountBand(number(cells, 2), number(cells, 4), number(cells, 5), DiscountMode.of(cells[6]), end);
    }

    private static BigDecimal number(String[] cells, int column) {
        try {
            return new BigDecimal(cells[column]);
        } catch (NumberFormatException refused) {
            throw new ScheduleError(
                    "'" + cells[column] + "' is not a number on the line " + String.join(" ", Arrays.asList(cells)));
        }
    }
}
