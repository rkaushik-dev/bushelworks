package io.github.rkaushikdev.bushelworks.domain.moisture;

import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A printed shrink chart: the percent a house takes off at each moisture reading.
 *
 * <p>Plenty of houses never quote a factor. They post a chart by the scale window, a row every half
 * point, and the chart is what the producer argues from. The rows are read the way the chart is
 * printed: a reading between two rows takes the row above it, because the chart says "to 17.5" and
 * 17.3 is to 17.5. Past the last row the house either adds so much a point, which the foot of most
 * charts says, or will not take the load at all.
 *
 * @param rows the rows, wettest last
 * @param beyondPerPoint the percent added for each point past the last row, if the chart runs on
 */
public record ShrinkChart(List<ShrinkChart.Row> rows, Optional<BigDecimal> beyondPerPoint) {

    /**
     * One line of the chart.
     *
     * @param moisture the reading the row runs to
     * @param percent the shrink at that reading
     */
    public record Row(BigDecimal moisture, BigDecimal percent) {

        public Row {
            Objects.requireNonNull(moisture, "moisture");
            Objects.requireNonNull(percent, "percent");
            if (percent.signum() < 0) {
                throw new MoistureError("a shrink chart row cannot add bushels: " + percent + "%");
            }
        }
    }

    public ShrinkChart {
        Objects.requireNonNull(beyondPerPoint, "beyondPerPoint");
        rows = List.copyOf(rows);
        if (rows.isEmpty()) {
            throw new MoistureError("a shrink chart needs at least one row");
        }
        for (int index = 1; index < rows.size(); index++) {
            Row before = rows.get(index - 1);
            Row row = rows.get(index);
            if (row.moisture().compareTo(before.moisture()) <= 0) {
                throw new MoistureError("shrink chart rows are out of order at " + row.moisture() + "%");
            }
            if (row.percent().compareTo(before.percent()) < 0) {
                throw new MoistureError("a wetter row cannot shrink less, at " + row.moisture() + "%");
            }
        }
        if (beyondPerPoint.isPresent() && beyondPerPoint.get().signum() <= 0) {
            throw new MoistureError("a chart that runs on has to add something a point");
        }
    }

    /** A chart that stops at its last row. */
    public static ShrinkChart of(List<Row> rows) {
        return new ShrinkChart(rows, Optional.empty());
    }

    /** The same chart, running on past its last row at so much a point. */
    public ShrinkChart runningOnAt(BigDecimal percentPerPoint) {
        return new ShrinkChart(rows, Optional.of(percentPerPoint));
    }

    /** The wettest reading the chart prints a row for. */
    public BigDecimal lastMoisture() {
        return rows.get(rows.size() - 1).moisture();
    }

    /**
     * The shrink the chart gives a reading above base.
     *
     * @throws MoistureError for a reading past the last row of a chart that stops there
     */
    public BigDecimal percentAt(BigDecimal moisture) {
        for (Row row : rows) {
            if (moisture.compareTo(row.moisture()) <= 0) {
                return Units.quantize(row.percent(), ShrinkSchedule.PERCENT_PLACES);
            }
        }
        if (beyondPerPoint.isEmpty()) {
            throw new MoistureError(moisture + "% is off the end of the shrink chart at " + lastMoisture() + "%");
        }
        BigDecimal past = moisture.subtract(lastMoisture());
        BigDecimal last = rows.get(rows.size() - 1).percent();
        return Units.quantize(last.add(past.multiply(beyondPerPoint.get())), ShrinkSchedule.PERCENT_PLACES);
    }
}
