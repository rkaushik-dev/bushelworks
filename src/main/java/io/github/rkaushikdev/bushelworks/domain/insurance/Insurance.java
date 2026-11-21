package io.github.rkaushikdev.bushelworks.domain.insurance;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/** Gathering production by unit, and the report the adjuster asks for. */
public final class Insurance {

    private static final List<String> COLUMNS = List.of(
            "unit_number",
            "party_code",
            "commodity_code",
            "crop_year",
            "acres",
            "approved_yield",
            "guaranteed_bushels",
            "delivered",
            "farm_stored",
            "total",
            "yield_per_acre",
            "shortfall");

    private Insurance() {}

    public static Production build(Unit unit, List<Delivery> deliveries) {
        return build(unit, deliveries, BigDecimal.ZERO);
    }

    /** Gather the deliveries this unit covers, ignoring the rest. */
    public static Production build(Unit unit, List<Delivery> deliveries, BigDecimal farmStored) {
        Production production = new Production(unit, farmStored);
        deliveries.stream().filter(delivery -> unit.covers(delivery.farmCode())).forEach(production::add);
        return production;
    }

    /** The production report, a row per unit by unit number, in the shape the adjuster asks for. */
    public static String toCsv(List<Production> reports) {
        StringBuilder out = new StringBuilder(Csv.row(COLUMNS));
        reports.stream()
                .sorted(Comparator.comparing(report -> report.unit().unitNumber()))
                .forEach(report -> {
                    Unit unit = report.unit();
                    out.append(Csv.row(
                            unit.unitNumber(),
                            unit.partyCode(),
                            unit.commodityCode(),
                            unit.cropYear(),
                            unit.acres(),
                            unit.approvedYield(),
                            unit.guaranteedBushels(),
                            report.delivered(),
                            report.farmStored(),
                            report.total(),
                            report.yieldPerAcre(),
                            report.shortfall()));
                });
        return out.toString();
    }

    /** The units short of their guarantee, biggest shortfall first. */
    public static List<Production> claims(List<Production> reports) {
        return reports.stream()
                .filter(Production::hasAClaim)
                .sorted(Comparator.comparing(Production::shortfall, Comparator.reverseOrder()))
                .toList();
    }

    /**
     * The gap between the production report and what was settled.
     *
     * <p>Should be zero. Anything else means a ticket carries the wrong farm code, and the adjuster
     * will find it before the office does.
     */
    public static BigDecimal reconcile(Production report, BigDecimal settledBushels) {
        return Units.quantize(report.delivered().subtract(settledBushels), Units.BUSHEL_PLACES);
    }
}
