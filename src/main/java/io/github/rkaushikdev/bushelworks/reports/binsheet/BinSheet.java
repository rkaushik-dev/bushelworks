package io.github.rkaushikdev.bushelworks.reports.binsheet;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.domain.bins.Bin;
import io.github.rkaushikdev.bushelworks.persistence.models.BinReadingRow;
import io.github.rkaushikdev.bushelworks.persistence.models.BinRow;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The bin sheet, which lives on a clipboard by the loader.
 *
 * <p>One line per bin: what is in it, how much, what it tests and how much room is left. The loader
 * operator reads it at seven in the morning to decide which bin a car comes out of, so the columns
 * are in the order he asks the questions: what is in it, how much, what does it test, can I put more
 * in.
 *
 * <p>The warm column is the one the manager looks at. A bin over the working temperature with the
 * fan off is the thing that turns into a spoiled bin, and it is easier to see on a printed sheet
 * than on any screen.
 */
public final class BinSheet {

    /** The sheet is this many characters wide. */
    public static final int WIDTH = 96;

    /** The house named on the sheet when nobody names another. */
    public static final String HOUSE_NAME = "BUSHELWORKS ELEVATOR";

    private static final List<String> CSV_HEADER = List.of(
            "bin_id",
            "commodity_code",
            "bushels",
            "capacity",
            "percent_full",
            "moisture",
            "test_weight",
            "headroom",
            "warmest",
            "fan_running",
            "out_of_service",
            "needs_attention");

    private static final Comparator<BinLine> BY_BIN = Comparator.comparing(BinLine::binId);

    private BinSheet() {}

    /** Flatten a bin row and every reading on it into one printable line. */
    public static BinLine lineFor(BinRow row) {
        return lineFor(row, null);
    }

    /**
     * Flatten a bin row and its recent readings into one printable line.
     *
     * @param since leave off readings taken before this moment, compared as instants; null keeps every
     *     reading
     */
    public static BinLine lineFor(BinRow row, OffsetDateTime since) {
        List<BinReadingRow> readings = row.getReadings().stream()
                .filter(reading -> since == null || !reading.getTakenAt().isBefore(since))
                .toList();
        Optional<BigDecimal> warmest =
                readings.stream().map(BinReadingRow::getTemperatureC).reduce(Decimals::max);
        boolean fan = readings.stream().anyMatch(BinReadingRow::isFanRunning);
        BigDecimal capacity = row.getCapacityBushels();
        BigDecimal bushels = row.getBushels();
        BigDecimal room = capacity.multiply(Bin.WORKING_FILL).subtract(bushels);
        return new BinLine(
                row.getBinId(),
                row.getCommodityCode().orElse(""),
                bushels,
                capacity,
                row.getMoisture(),
                row.getTestWeight(),
                room.signum() > 0 ? room : BigDecimal.ZERO,
                warmest,
                fan,
                row.isOutOfService());
    }

    /** The sheet for the house, as it goes on the clipboard. */
    public static String render(List<BinLine> lines, String locationCode) {
        return render(lines, locationCode, HOUSE_NAME);
    }

    /**
     * The sheet as it goes on the clipboard: a line per bin in bin order, a total, and the bins to look
     * at today. Bushels and room print as whole bushels; a reading prints whole, or {@code -} when
     * there is none. A bin out of service is noted {@code OUT}, one warm with the fan off {@code LOOK}.
     */
    public static String render(List<BinLine> lines, String locationCode, String houseName) {
        List<String> out = new ArrayList<>();
        out.add(Columns.centred(houseName, WIDTH));
        out.add(Columns.centred("BIN SHEET  " + locationCode, WIDTH));
        out.add("=".repeat(WIDTH));
        out.add(Columns.left("BIN", 8)
                + Columns.left("CROP", 7)
                + Columns.right("BUSHELS", 12)
                + Columns.right("FULL", 8)
                + Columns.right("MOIST", 8)
                + Columns.right("TEST WT", 9)
                + Columns.right("ROOM", 12)
                + Columns.right("WARM", 8)
                + Columns.right("FAN", 6)
                + Columns.right("NOTE", 10));
        out.add("-".repeat(WIDTH));
        for (BinLine line : lines.stream().sorted(BY_BIN).toList()) {
            String note = line.outOfService() ? "OUT" : line.needsAttention() ? "LOOK" : "";
            out.add(Columns.left(line.binId(), 8)
                    + Columns.left(line.commodityCode().isEmpty() ? "-" : line.commodityCode(), 7)
                    + Columns.right(whole(line.bushels()), 12)
                    + Columns.right(line.percentFull().toString(), 7)
                    + "%"
                    + Columns.right(cell(line.moisture()), 8)
                    + Columns.right(cell(line.testWeight()), 9)
                    + Columns.right(whole(line.headroom()), 12)
                    + Columns.right(cell(line.warmest()), 8)
                    + Columns.right(line.fanRunning() ? "yes" : "no", 6)
                    + Columns.right(note, 10));
        }
        out.add("-".repeat(WIDTH));
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal room = BigDecimal.ZERO;
        for (BinLine line : lines) {
            total = total.add(line.bushels());
            room = room.add(line.headroom());
        }
        out.add(Columns.left("TOTAL", 8)
                + " ".repeat(7)
                + Columns.right(whole(total), 12)
                + " ".repeat(8 + 8 + 9)
                + Columns.right(whole(room), 12));
        List<String> attention = lines.stream()
                .filter(BinLine::needsAttention)
                .map(BinLine::binId)
                .sorted()
                .toList();
        if (!attention.isEmpty()) {
            out.add("");
            out.add("LOOK AT TODAY: " + String.join(", ", attention));
        }
        return String.join("\n", out);
    }

    /**
     * The sheet as comma separated text: a header and a row per bin in bin order, quantities at the
     * places they were kept, an unmeasured reading left blank and flags as {@code yes} or {@code no}.
     */
    public static String toCsv(List<BinLine> lines) {
        StringBuilder out = new StringBuilder(Csv.row(CSV_HEADER));
        for (BinLine line : lines.stream().sorted(BY_BIN).toList()) {
            out.append(Csv.row(
                    line.binId(),
                    line.commodityCode(),
                    line.bushels(),
                    line.capacity(),
                    line.percentFull(),
                    line.moisture(),
                    line.testWeight(),
                    line.headroom(),
                    line.warmest(),
                    yesNo(line.fanRunning()),
                    yesNo(line.outOfService()),
                    yesNo(line.needsAttention())));
        }
        return out.toString();
    }

    /** Everywhere a load of this commodity could still go: the room in every bin in service that is empty or holds it. */
    public static BigDecimal roomFor(List<BinLine> lines, String commodityCode) {
        BigDecimal room = BigDecimal.ZERO;
        for (BinLine line : lines) {
            boolean takes =
                    line.commodityCode().isEmpty() || line.commodityCode().equals(commodityCode);
            if (!line.outOfService() && takes) {
                room = room.add(line.headroom());
            }
        }
        return room;
    }

    /**
     * A quantity or a reading to the whole number, a tie going to the even one. A reading just below
     * freezing that rounds to nothing keeps its sign, {@code -0}, so the sheet still shows which side
     * of zero the bin was on.
     */
    private static String whole(BigDecimal value) {
        BigDecimal rounded = value.setScale(0, RoundingMode.HALF_EVEN);
        return rounded.signum() == 0 && value.signum() < 0 ? "-0" : rounded.toString();
    }

    private static String cell(Optional<BigDecimal> value) {
        return value.map(BinSheet::whole).orElse("-");
    }

    private static String yesNo(boolean flag) {
        return flag ? "yes" : "no";
    }
}
