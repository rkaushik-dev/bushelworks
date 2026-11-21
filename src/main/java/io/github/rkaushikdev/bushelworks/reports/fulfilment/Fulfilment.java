package io.github.rkaushikdev.bushelworks.reports.fulfilment;

import static io.github.rkaushikdev.bushelworks.reports.Columns.clip;
import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * How the contract book is filling.
 *
 * <p>The desk asks this twice a week from October to Christmas. It is not a list of contracts; it is
 * a list of the ones that will not fill, because those are the ones that turn into a phone call
 * while there is still time to do something. See {@link FulfilmentState} for the states that
 * matter.
 */
public final class Fulfilment {

    /** The width of the printed sheet. */
    public static final int WIDTH = 90;

    private Fulfilment() {}

    /** Assess one contract against what has landed on it, for a producer with no hauling rate. */
    public static FulfilmentLine lineFor(PurchaseContract contract, BigDecimal applied, LocalDate asOf) {
        return lineFor(contract, applied, asOf, BigDecimal.ZERO);
    }

    /**
     * Assess one contract against what has landed on it.
     *
     * @throws FulfilmentError when the bushels applied are negative
     */
    public static FulfilmentLine lineFor(
            PurchaseContract contract, BigDecimal applied, LocalDate asOf, BigDecimal weeklyRate) {
        if (applied.signum() < 0) {
            throw new FulfilmentError(contract.number() + ": applied cannot be negative");
        }
        return new FulfilmentLine(
                contract.number(),
                contract.partyId(),
                contract.commodityCode(),
                contract.bushels(),
                applied,
                contract.window().closes(),
                asOf,
                weeklyRate,
                contract.tolerancePercent());
    }

    /** The sheet the desk works down: by state, then contract, and the calls to make at the foot. */
    public static String render(List<FulfilmentLine> lines, LocalDate asOf) {
        List<String> out = new ArrayList<>();
        out.add(Columns.centred("CONTRACT FULFILMENT " + asOf, WIDTH));
        out.add("=".repeat(WIDTH));
        out.add(left("CONTRACT", 16)
                + left("PRODUCER", 16)
                + left("CROP", 6)
                + right("CONTRACTED", 14)
                + right("APPLIED", 14)
                + right("OUTSTANDING", 14)
                + right("STATE", 10));
        out.add("-".repeat(WIDTH));
        List<FulfilmentLine> sorted = lines.stream()
                .sorted(Comparator.comparing(
                                (FulfilmentLine line) -> line.state().value())
                        .thenComparing(FulfilmentLine::number))
                .toList();
        for (FulfilmentLine line : sorted) {
            out.add(left(line.number(), 16)
                    + left(clip(line.partyCode(), 14), 16)
                    + left(line.commodityCode(), 6)
                    + right(wholeBushels(line.contracted()), 14)
                    + right(wholeBushels(line.applied()), 14)
                    + right(wholeBushels(line.outstanding()), 14)
                    + right(line.state().value(), 10));
        }
        out.add("-".repeat(WIDTH));
        List<FulfilmentLine> calls =
                lines.stream().filter(FulfilmentLine::needsACall).toList();
        out.add(lines.size() + " contracts, " + calls.size() + " need a call");
        if (!calls.isEmpty()) {
            out.add("");
            out.add("RING TODAY: "
                    + calls.stream().map(FulfilmentLine::number).sorted().collect(Collectors.joining(", ")));
        }
        return String.join("\n", out);
    }

    /** Bushels to the whole bushel, a half going to the even one. */
    private static String wholeBushels(BigDecimal bushels) {
        return bushels.setScale(0, RoundingMode.HALF_EVEN).toString();
    }

    /** One row per contract, by contract number, weeks left to a tenth. */
    public static String toCsv(List<FulfilmentLine> lines) {
        StringBuilder out = new StringBuilder();
        out.append(Csv.row(
                "number",
                "party_code",
                "commodity_code",
                "contracted",
                "applied",
                "outstanding",
                "closes",
                "weeks_left",
                "deliverable",
                "state"));
        List<FulfilmentLine> byNumber = lines.stream()
                .sorted(Comparator.comparing(FulfilmentLine::number))
                .toList();
        for (FulfilmentLine line : byNumber) {
            out.append(Csv.row(
                    line.number(),
                    line.partyCode(),
                    line.commodityCode(),
                    line.contracted(),
                    line.applied(),
                    line.outstanding(),
                    line.closes().toString(),
                    line.weeksLeft().setScale(1, RoundingMode.HALF_EVEN),
                    line.deliverable(),
                    line.state().value()));
        }
        return out.toString();
    }

    /** The open contracts the producer is not hauling fast enough to fill. */
    public static List<FulfilmentLine> atRisk(List<FulfilmentLine> lines) {
        return lines.stream()
                .filter(line -> line.state() == FulfilmentState.AT_RISK)
                .toList();
    }

    /** The contracts past their window with bushels still to come: the defaults. */
    public static List<FulfilmentLine> shortList(List<FulfilmentLine> lines) {
        return lines.stream()
                .filter(line -> line.state() == FulfilmentState.SHORT)
                .toList();
    }

    /** Every contract's outstanding bushels, added up. */
    public static BigDecimal outstandingTotal(List<FulfilmentLine> lines) {
        BigDecimal sum = BigDecimal.ZERO;
        for (FulfilmentLine line : lines) {
            sum = sum.add(line.outstanding());
        }
        return Units.quantize(sum, Units.BUSHEL_PLACES);
    }
}
