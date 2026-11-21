package io.github.rkaushikdev.bushelworks.domain.position;

import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Every commodity and location for one day. */
public final class PositionReport {

    private final LocalDate asOf;
    private final List<PositionLine> lines = new ArrayList<>();

    public PositionReport(LocalDate asOf) {
        this.asOf = asOf;
    }

    public LocalDate asOf() {
        return asOf;
    }

    public List<PositionLine> lines() {
        return List.copyOf(lines);
    }

    public void add(PositionLine line) {
        if (!line.asOf().equals(asOf)) {
            throw new PositionError("line dated " + line.asOf() + " does not belong on the " + asOf + " report");
        }
        lines.add(line);
    }

    /** One rolled up line for a commodity across every location. */
    public PositionLine byCommodity(String commodityCode) {
        List<PositionLine> matching = lines.stream()
                .filter(line -> line.commodityCode().equals(commodityCode))
                .toList();
        if (matching.isEmpty()) {
            throw new PositionError("no " + commodityCode + " position on " + asOf);
        }
        PositionLine rolled = matching.get(0);
        for (PositionLine line : matching.subList(1, matching.size())) {
            rolled = rolled.combine(line);
        }
        return rolled;
    }

    /** Every commodity with a line, sorted. */
    public List<String> commodities() {
        return lines.stream()
                .map(PositionLine::commodityCode)
                .distinct()
                .sorted()
                .toList();
    }

    public BigDecimal totalOwned() {
        BigDecimal sum = BigDecimal.ZERO;
        for (PositionLine line : lines) {
            sum = sum.add(line.owned());
        }
        return Units.quantize(sum, Units.BUSHEL_PLACES);
    }

    public List<PositionLine> shorts() {
        return lines.stream().filter(PositionLine::isShort).toList();
    }
}
